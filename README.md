# Forge

A **Java-first framework for building IDE products** — not a single IDE.

The backend owns everything that matters: workspaces, files, commands, events, sessions,
extensions, terminals, tasks, search, source control, language tooling and debugging. The
browser frontend renders, and nothing more. The same assembly runs on a laptop, in Docker, and
in a hosted environment, because where a workspace physically lives is a provider's business and
nothing above that provider is allowed to care.

Visual Studio Code inspired the *experience* — workbench layout, command palette, keybindings,
contribution points. Eclipse Theia inspired the *shape* — a framework that products are built
from, with replaceable capabilities and a clean frontend/backend split. Neither is cloned.

---

## Quick start

You need **Git**, **Docker** and **Docker Compose**. You do not need Java or Node on the host.

```bash
git clone <repository>
cd ForgeJavaIDE
export IDE_AUTH_PASSWORD='choose-a-strong-password'
docker compose up --build
```

Then open **<http://localhost:3000>** and sign in:

| | |
|---|---|
| Username | `developer` |
| Password | the value you set in `IDE_AUTH_PASSWORD` |

The IDE opens the directory mounted at `/workspace`. By default that is `./workspace` in this
repository. To work on something real:

```bash
IDE_WORKSPACE=~/code/my-project docker compose up --build
```

`IDE_WORKSPACE` may also point at a folder *of* projects — the root and each immediate
subdirectory are offered as separate workspaces, switchable from the status bar.

> Docker Compose intentionally refuses to start until `IDE_AUTH_PASSWORD` is set and publishes
> port 3000 on host loopback only. Direct non-Docker startup also requires an explicit
> unique password of at least 12 characters, on every bind address.

### Everyday commands

```bash
docker compose up --build        # build and start
docker compose up -d             # start in the background
docker compose logs -f           # follow logs
docker compose down              # stop and remove the container
docker compose down -v           # also delete the forge-data volume (settings, workbench state)
docker compose ps                # health status
```

`docker compose down` keeps your settings and workbench state: they live in the named volume
`forge-data`. **`docker compose down -v` deletes them** — that is how you reset local state.
Your files are never in that volume; they are in whatever directory you mounted.

### Is it up?

```bash
curl -s localhost:3000/api/health
# {"status":"ready"}
```

The health check is readiness, not liveness: it answers `503` until the application can actually
serve requests, so `docker compose up --wait` waits for something useful.

---

## What works today

Sign in → a workspace opens → browse files → open one → edit → `Ctrl`/`Cmd`+`S` → the
`file.save` command runs in Java → the file is written → a `FileSaved` event reaches every
session that has the workspace open.

Beyond that first milestone:

- **Command palette** (`Ctrl`/`Cmd`+`Shift`+`P`) listing interactive workbench commands and
  extension commands; low-level commands that require raw ids/paths stay behind their dedicated UI
- **Quick open** (`Ctrl`/`Cmd`+`P`) backed by workspace-wide file search
- **Find in files** (`Ctrl`/`Cmd`+`Shift`+`F`), cancellable with `Esc`
- **Editor groups**, tabs, dirty state, split editor, Monaco integration
- **Terminals** — line-oriented, running in the backend's environment
- **Tasks** from `.forge/tasks.json`, presented in a terminal but tracked by the task engine
- **Source control** — status, staging, commit, branches, diff, history, fetch/pull/push
- **Language tooling** — completion and hover through the provider interface, with a
  buffer-word provider registered so the pipeline works out of the box
- **Settings** with `defaults → extension → user → workspace` layering
- **Extensions** loaded from jars, activated lazily, isolated from failure
- **Breakpoints** — set and kept per workspace, ready for an adapter

Not implemented, deliberately: a debug adapter, collaboration, roles and permissions, and tests.
The architecture leaves room for all of them; none is faked.

---

## Development without Docker

Docker is a convenience, never a requirement — no core behaviour hides behind it.

```bash
# Terminal 1 — backend (Java 21, Maven)
mvn -q package
IDE_WORKSPACE_ROOT=$PWD/workspace \
IDE_DATA_DIR=$PWD/.forge-data \
IDE_WEB_ROOT=$PWD/frontend/dist \
IDE_EXTENSIONS_DIR=$PWD/backend/forge-ext-demo/target \
java -jar backend/forge-app/target/forge-app.jar

# Terminal 2 — frontend with hot reload
cd frontend && npm install && npm run dev
```

The Vite dev server on <http://localhost:5173> proxies `/api` to the backend, so the browser
still sees one origin. Java 21 is the single baseline: `<java.version>` in `pom.xml` and
`ARG JAVA_VERSION` in the `Dockerfile` are the same number.

---

## Configuration

Everything is an environment variable with a working local default. There is no configuration
file to edit, and no variable controls architecture — only paths, ports and limits.

| Variable | Default | Meaning |
|---|---|---|
| `IDE_PORT` | `3000` | HTTP port |
| `IDE_HOST` | `127.0.0.1` | Bind address for direct runs; Docker overrides this inside the container while publishing only host loopback |
| `IDE_WORKSPACE_ROOT` | `/workspace` | Directory the local workspace provider serves |
| `IDE_DATA_DIR` | `/data` | Settings and persisted state |
| `IDE_WEB_ROOT` | `/app/web` | Built frontend assets |
| `IDE_EXTENSIONS_DIR` | `/app/extensions` | Extension jars |
| `IDE_LOG_LEVEL` | `INFO` | `DEBUG`, `INFO`, `WARN`, `ERROR` |
| `IDE_AUTH_USER` | `developer` | The single configured identity |
| `IDE_AUTH_PASSWORD` | Required, no default | At least 12 characters; hashed with PBKDF2 at startup |
| `IDE_SHELL` | `/bin/bash` in the image | Shell used for terminals |
| `IDE_TERMINAL_ENABLED` | `true` | Set `false` to refuse process execution entirely |
| `IDE_SESSION_IDLE_MINUTES` | `120` | Sliding session idle timeout |
| `IDE_SESSION_MAX_HOURS` | `12` | Hard maximum session lifetime |
| `IDE_MAX_FILE_MB` | `8` | Largest file the editor will read or write |

### Workspace tasks

Create `.forge/tasks.json` inside a workspace:

```json
{
  "tasks": [
    { "id": "build", "name": "Build", "type": "build", "command": "mvn", "args": ["-B", "package"] },
    { "id": "test",  "name": "Test",  "type": "test",  "command": "npm", "args": ["test"], "cwd": "frontend" }
  ]
}
```

They appear in the Tasks panel and as `task.run` arguments.

---

## Architecture in one page

```
Browser (TypeScript)                  CLI / automation / AI agent
        |                                        |
        +--------------------+-------------------+
                             v
                    Transport Gateway            HTTP + server-sent events
                             |
              commands  |  queries  |  events
                             v
                      Application Layer          CommandRegistry, CommandExecutor,
                             |                   QueryRegistry, EventBus, Authorizer
                             v
                        Feature APIs             workspace, filesystem, editor, auth,
                             |                   settings, state, search, terminal, tasks,
                             v                   language, scm, debug
                    Capability Interfaces        FileSystem, WorkspaceProvider,
                             |                   TerminalProvider, LanguageProvider,
                             v                   SourceControlProvider, AuthenticationProvider,
                       Infrastructure            DebugAdapter, TaskProvider, StateStore
```

Three ideas carry most of the weight:

**Commands are the only way to make something happen.** A keystroke, a menu item, the palette,
an extension, a script and a future agent all name the same command id and get the same
validation, authorisation, logging and cancellation — applied once, in `CommandExecutor`.

**Queries are reads and stay separate.** `currentWorkspace()` is not `GetWorkspaceCommand`.

**Events describe what already happened.** They never ask for anything, and the event bus is not
a back door around a feature's API.

Everything else follows from keeping the workspace central and its location abstract.

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — the reasoning, the dependency rules, and the
  decisions deliberately *not* taken
- [docs/PROTOCOL.md](docs/PROTOCOL.md) — the four endpoints, with `curl` examples that drive the
  IDE without a browser
- [docs/EXTENSIONS.md](docs/EXTENSIONS.md) — writing an extension, and replacing a capability

### Repository layout

```
ForgeJavaIDE/
├── backend/
│   ├── forge-core/        framework core + feature APIs — JDK only, no dependencies
│   ├── forge-app/         transport, infrastructure, composition root
│   └── forge-ext-demo/    a tiny extension, to keep the extension boundary honest
├── frontend/              TypeScript workbench (Vite + Monaco)
├── docs/                  architecture, protocol, extension authoring
├── Dockerfile             frontend build + backend build + slim runtime
└── compose.yaml           one service; no database, cache or broker
```

Three Maven modules, not one per feature. Features are isolated by package boundaries and
explicit dependencies, not by build files or network hops — a modular monolith, on purpose.

---

## Extending it

An extension is a jar with a `forge-extension.json` manifest, built against `forge-core` alone.
It reaches the IDE only through `ExtensionContext` — the same command and query layer the
frontend uses. See [docs/EXTENSIONS.md](docs/EXTENSIONS.md) and
[`DemoExtension.java`](backend/forge-ext-demo/src/main/java/dev/forge/ext/demo/DemoExtension.java).

Replacing a capability is a one-line change in `ForgeApplication`: implement `FileSystem`,
`WorkspaceProvider`, `TerminalProvider`, `AuthenticationProvider`, `SourceControlProvider`,
`LanguageProvider`, `DebugAdapter`, `TaskProvider` or `StateStore` and wire yours instead.

---

## Security

Security is a starting constraint here, not a later pass.

- **The frontend is never trusted.** Every privileged operation is re-validated in Java. A
  disabled menu item is presentation; `CommandExecutor` is the authority.
- **Workspace containment.** Paths are normalised once in `Resource`, and `LocalFileSystem`
  re-checks the *real* path against the root, so a symlink inside a workspace cannot escape it.
- **Workspace access.** A session may only act on a workspace it has attached to — knowing an id
  is not enough.
- **Credentials.** Passwords are PBKDF2-hashed; session tokens are 256 random bits, stored only
  as a SHA-256 hash and returned exactly once. Tokens travel in the `Authorization` header, never
  a cookie, so there is no CSRF surface. The frontend keeps the token in memory only.
- **Sensitive commands** cannot be replaced by an extension, and their results are stripped from
  broadcast events so a login token cannot leak onto the event stream.
- **Child processes** get a minimal environment, not the server's — nothing started in a terminal
  can read `IDE_AUTH_PASSWORD`.
- **Extension namespaces** are reserved: an extension cannot register `file.delete`.
- **The container** runs as a non-root user with `no-new-privileges` and all capabilities
  dropped. Nothing of the host is mounted except the workspace directory you choose.
- **Logs** are structured and redact anything that looks like a secret. Stack traces stay on the
  server; clients receive a structured error code.

---

## Scope

Production source files: **121** — 99 Java, 22 TypeScript — against a hard budget of 700. The count is
low because abstractions had to earn their place: no `Impl` twins, no DTO-and-mapper pairs, no
handler class per command. `mvn -q package` and `npm run build` both succeed; there are no tests
yet, by design.
