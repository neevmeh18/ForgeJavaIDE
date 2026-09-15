# Architecture

This document explains *why* the framework is shaped the way it is. The code is meant to be
readable on its own; what follows is the reasoning that does not fit in a comment.

---

## 1. What this is

Forge is a framework for building IDE **products**, not an IDE. The distinction drives
everything: nothing is hard-coded that a product might reasonably want to change, and the parts
a product replaces are named capabilities with explicit interfaces rather than classes to
subclass.

Java owns application behaviour. TypeScript renders. There is no third place where a rule might
live, and no rule implemented twice.

---

## 2. Layers and dependency direction

```
UI ──────────► Transport ──────────► Application ──────────► Features
                                                                │
                                                                ▼
                                                          Capabilities
                                                                ▲
                                                                │
                                                        Infrastructure
```

Arrows are compile-time dependencies. Three rules keep them honest:

1. **Features never depend on the transport or the UI.** Nothing in `forge-core` imports
   Jackson, `HttpExchange`, or anything with "http" in its name. `forge-core` has *no* third-party
   dependencies at all — that is the property that proves it.
2. **Infrastructure implements contracts; it does not define them.** `FileSystem` lives with the
   filesystem feature; `LocalFileSystem` lives in `forge-app`. The dependency points inward.
3. **Features do not reach into each other's internals.** The editor uses `FileService`, never
   `LocalFileSystem`. Source control goes through its provider, never through JGit types leaking
   upward. Terminals know a `WorkspaceId`, never a `Path`.

### Breaking cycles without ceremony

Two places wanted a cycle, and both were resolved with a narrow functional interface supplied at
assembly time rather than with an extra module or an event-based workaround:

- The filesystem feature needs to know which `FileSystem` serves a workspace, but must not
  import the workspace feature. `FileSystem.Locator` is one method; `WorkspaceService`
  implements it; `ForgeApplication` passes it in.
- Language tooling needs live buffer text, but must not import the editor.
  `LanguageService.DocumentSource` is one method; the bootstrap supplies a lambda over
  `EditorService`.

The editor, in turn, is allowed to depend on language tooling — so `editor.format` can be an
editor command implemented by a language provider, with the dependency running one way only.

---

## 3. The command pattern

The single most important decision. Every meaningful action is a `CommandId` in a registry, and
every invocation goes through one executor.

```
shortcut │ menu │ palette │ extension │ CLI │ automation │ agent
                              │
                              ▼
                       CommandRegistry          what exists
                              │
                              ▼
                       CommandExecutor          what is allowed, and what happened
                              │
                              ▼
                          Handler
                              │
                              ▼
                          Feature
```

**Registration and execution are separate.** The registry is a catalogue with no business logic.
The executor is where argument checks, session and workspace preconditions, authorisation,
availability, tracing, cancellation and error normalisation happen — once, for every command,
including every command an extension contributes. A handler cannot forget a check, because it
never performs one.

**Handlers are lambdas by default.** `commands.register(descriptor, files::writeText)` is the
common case. A dedicated handler class is justified when a command orchestrates several features
or carries substantial workflow — not by the existence of a command. Turning `file.save` into six
files would make it harder to understand, not easier.

**Command ids are namespaced and stable.** `CommandId` rejects a bare `run` or `deploy` at
construction. Once extensions contribute commands, unqualified verbs collide and a keybinding
has no way to say whose `deploy` it meant.

**Execution is always asynchronous.** Handlers run on virtual threads; the caller gets a
`CommandExecution` with a state (`QUEUED`, `RUNNING`, `COMPLETED`, `FAILED`, `CANCELLED`), a
future, and a cancellation token. Cancellation is framework-neutral: it is not a thread
interrupt and not tied to an HTTP connection, because a command can be cancelled from the
palette, by a CLI, or because a collaborator closed the workspace.

The gateway waits briefly for a result and, if the command is still going, returns `pending`
with the execution id — the outcome then arrives on the event stream. That is how "Find in
files" cancels with `Esc`: the frontend holds the execution id and runs `command.cancel`.

### Four things that are not the same

| | |
|---|---|
| **registration** | claiming an unused id; a clash is a `CONFLICT`, never a silent overwrite |
| **decoration/interception** | `CommandInterceptor` wraps execution; the owner keeps the id |
| **replacement** | an explicit, logged `replace()` call — refused for sensitive commands |
| **contribution** | a menu item or keybinding pointing at an id someone else owns |

An extension gets interception and contribution. It cannot become `auth.login` or `file.delete`,
and it cannot register inside a reserved namespace.

---

## 4. Commands, queries, events

```
COMMAND   "Do something."          workspace.open, file.save, task.run
QUERY     "Give me information."   workspace.current, file.list, language.hover
EVENT     "Something happened."    WorkspaceOpened, FileSaved, TerminalExited
```

Queries are synchronous, side-effect free, and skip the command machinery entirely — undo,
availability and audit mean nothing for "list this directory". There is no
`GetActiveEditorCommand`.

The line between the two is cost, not purity: **search is a command**, because it is slow and
must be cancellable, and those are exactly the properties the command pipeline provides.

Events are notifications about the past. They never request action, and no feature uses the
event bus in place of another feature's API. They do carry scope — a `workspaceId`, sometimes a
`sessionId` — which is what lets the transport deliver each one only to the sessions it concerns.

---

## 5. Workspace and session

The workspace is first-class and mandatory. Its identity is **not** its location:

```java
record Workspace(WorkspaceId id, String name, Location location, ...)
record Location(String scheme, String authority, String path)
```

`Location` is opaque to every feature. Only the `WorkspaceProvider` that produced a workspace
interprets it. The local provider hashes a relative directory path into a stable, opaque id;
nothing outside that class can turn an id back into a path. That is the property that makes
local, container, SSH and cloud workspaces the same thing to the editor, to search, and to
source control.

**A workspace is not a user.** Sessions *attach* to a workspace; several can be attached at once,
and one user can hold several sessions. The workspace stays open while anyone is attached.
Nothing in the framework assumes otherwise, which is why presence, shared editing and follow mode
are later features rather than later rewrites. Documents are shared per `(workspace, path)` for
the same reason.

---

## 6. Capabilities

Interfaces exist where replacement is a real requirement, and nowhere else:

`FileSystem`, `WorkspaceProvider`, `AuthenticationProvider`, `TerminalProvider`,
`LanguageProvider`, `SourceControlProvider`, `DebugAdapter`, `TaskProvider`, `StateStore`.

There is no `WorkspaceServiceInterface`, no `FileServiceImpl`, no factory for objects that are
constructed once. An interface with exactly one implementation and no prospect of a second is a
file with no boundary in it.

---

## 7. Transport

HTTP plus server-sent events, with four endpoints:

```
POST /api/command     POST /api/query     GET /api/events     GET /api/health
```

Not one controller per feature. The gateway translates and does nothing else; `Gateway` is the
only class that turns a bearer token into a `RequestContext`, and nothing downstream reads a
header. Adding WebSocket or JSON-RPC means adding a sibling of `HttpTransport`.

**Why SSE rather than WebSocket.** The traffic is one-directional: clients send discrete
request/response calls and receive a stream of events. SSE gives that with less machinery, works
through proxies, and reconnects on its own. Terminal input is a command (`terminal.write`) and
terminal output is an event — which is both simpler and a better fit for the model than a
bespoke bidirectional channel. The event stream is read with `fetch`, not `EventSource`, so the
token travels in a header like every other call.

**Why the JDK's HTTP server.** It is enough for four endpoints, and it keeps a web framework out
of the dependency graph. Virtual threads make blocking handlers and thousands of idle event
streams cheap. Jackson is the one runtime dependency, confined to the transport and to two
infrastructure adapters that persist JSON.

---

## 8. Extensions

An extension is compiled against `forge-core` alone and sees exactly one class:
`ExtensionContext`. It has no access to `FileService`, `WorkspaceService`, the transport or the
database. It reaches features the way the frontend does — by executing commands and running
queries.

That is not a limitation dressed up as a principle. It means an extension gets the same
validation and authorisation as any other caller, it cannot depend on a feature's internals, and
the framework can change those internals without breaking installed extensions.

Activation is lazy and condition-driven (`onCommand:`, `onLanguage:`, `onWorkspace`,
`onStartup`). An unknown command id gives extensions a chance to claim it — `CommandRegistry`
has a resolver hook, so `CommandExecutor` never learns that extensions exist. Failure is
contained: the extension is marked `FAILED`, its registrations are rolled back, an event is
published, and the IDE carries on.

---

## 9. Errors, logging, state

Failures are `ForgeException` with a `Code` — `NOT_FOUND`, `INVALID_ARGUMENT`, `CONFLICT`,
`UNAUTHORIZED`, `FORBIDDEN`, `UNAVAILABLE`, `UNSUPPORTED`, `CANCELLED`, `INTERNAL_FAILURE`.
Transports map codes without reading messages. `INTERNAL_FAILURE` becomes a generic message to
the client and a full stack trace in the log, never the reverse.

Logging is `System.Logger` with correlation fields (`workspaceId`, `userId`, `sessionId`,
`commandId`, `extensionId`) and central redaction of anything whose key looks sensitive — so a
careless call site cannot leak a token.

State is explicit and scoped (`APPLICATION`, `USER`, `WORKSPACE`, `SESSION`; session state is
never persisted). Settings borrow the state store rather than inventing persistence, and resolve
`WORKSPACE > USER > EXTENSION > DEFAULT`, reporting which layer won so a settings UI can show
"overridden here".

---

## 10. Local / Docker / cloud symmetry

There is no `if (docker)` anywhere, and there is no Docker-specific assembly. Docker supplies
paths, a port and a workspace mount; `ForgeApplication` is constructed identically on a laptop.
What differs between deployments is which implementation of a capability is wired — and that is
one line of the composition root, by design.

A single runtime container serves both the API and the frontend bundle. One origin means no
CORS, no cross-port cookie problems, and one thing to run.

---

## 11. Decisions deliberately not taken

- **No dependency-injection container.** One readable composition root beats annotations in
  sixty files, and it makes the dependency direction impossible to fake.
- **No microservices, broker, or event sourcing.** Isolation comes from architectural
  boundaries. Network boundaries would add failure modes and buy nothing at this size.
- **No database.** Settings and workbench state are kilobytes. `StateStore` is the seam for the
  day that changes.
- **No pseudo-terminal library.** That means a native dependency; the honest consequence — line
  oriented terminals, no curses programs — is documented, and a PTY provider is a drop-in.
- **No Git library.** The `git` binary is the reference implementation and is already installed.
  All Git-specific behaviour stays inside `GitSourceControlProvider`.
- **No UI framework.** The workbench tree is shallow and event-driven, and products are expected
  to replace the shell anyway.
- **No tests yet**, as specified. The code is structured to be testable — explicit
  constructor dependencies, no static service locator, no ambient user or workspace, features
  isolated behind interfaces — which is the part that is expensive to retrofit.

---

## 12. File-count discipline

99 Java and 22 TypeScript files, against a budget of 700. The budget was not met by cutting
structure; it was met by refusing structure that carried none:

- Identifier value types live together in `Ids.java`. Nine one-line records in nine files would
  create no boundary. Nested types import individually, so call sites read normally.
- Records for a domain vocabulary — `LanguageTypes`, `ScmTypes`, `DebugTypes` — are one file
  each. Fifteen files of two-line records is worse to navigate, not better.
- Each feature is roughly: the model, a service, a `*Commands` registration class, an `*Events`
  class, and a capability interface where replacement matters. That pattern multiplied across
  thirteen features is about sixty files, which is why it was chosen.
- No `Impl` twins, no DTO/mapper pairs, no validator or factory per action. Where a transport
  needs a different shape from a domain type — command descriptors carry an availability
  function that has no business on the wire — an explicit view record is written at that one
  boundary, not generated everywhere.
