# Writing an extension

An extension is a jar containing a manifest and a class implementing `Extension`. It is
compiled against **`forge-core` only** and is handed exactly one object: `ExtensionContext`.

That is the whole public API. There is no access to `FileService`, `WorkspaceService`, the
transport or the state store — an extension reaches features the way the frontend and a CLI do,
by executing commands and running queries. It therefore gets the same argument validation,
authorisation and auditing as any other caller, and the framework can change a feature's
internals without breaking anything installed.

---

## 1. Manifest

`src/main/resources/forge-extension.json`, at the root of the jar:

```json
{
  "id": "robotics",
  "name": "Robotics Tools",
  "version": "1.0.0",
  "description": "Deployment commands for robot targets.",
  "main": "com.example.robotics.RoboticsExtension",
  "activationEvents": ["onCommand:robotics.deploy", "onLanguage:python"]
}
```

Discovery reads this without loading a single class, so an extension that is never needed is
never loaded.

### Activation events

| Event | When |
|---|---|
| `onCommand:<id>` | someone tries to run the command — including from the palette or a script |
| `onLanguage:<id>` | a document of that language is involved |
| `onFileType:<ext>` | a file of that extension is opened |
| `onWorkspace` | any workspace opens |
| `onStartup` | at boot — use sparingly; it costs every launch |

`onCommand:` works because `CommandRegistry` asks its resolvers before reporting an id unknown.
The executor never learns that extensions exist.

---

## 2. The extension class

```java
public final class RoboticsExtension implements Extension {

    @Override
    public void activate(ExtensionContext ctx) {
        ctx.registerCommand(
            CommandDescriptor.of("robotics.deploy", "Robotics", "Deploy to Robot")
                .describedAs("Builds and uploads the current workspace")
                .workspaceScoped(),
            command -> {
                String target = command.args().requiredString("target");
                // Reach other features the same way any client would.
                Object status = ctx.runQuery("scm.status", Args.EMPTY, command.request());
                ctx.executeCommand("task.run", Args.of("taskId", "build"), command.request());
                return Map.of("target", target, "scm", status);
            });

        ctx.contributeMenuItem("menu.view", "robotics.deploy", "Deploy…", "robotics", 100);
        ctx.contributeKeybinding("ctrl+alt+d", "robotics.deploy", null);

        ctx.subscribe(FileEvents.FileSaved.class, saved -> ctx.log().debug("saved " + saved.path()));
    }

    @Override
    public void deactivate() {
        // Commands, queries, contributions and subscriptions are released automatically.
    }
}
```

`ExtensionContext` offers:

| | |
|---|---|
| `registerCommand`, `registerQuery` | contribute behaviour |
| `executeCommand`, `runQuery` | use the rest of the IDE |
| `subscribe`, `subscribeAll`, `publish` | react to and raise events |
| `intercept` | decorate an existing command without taking it over |
| `contributeMenuItem`, `contributeKeybinding`, `contributeView` | appear in the workbench |
| `log`, `onDispose`, `id` | plumbing |

For persistence, use the `state.set` command and `state.get` query rather than a store handle —
the same rule applied consistently.

---

## 3. Rules the framework enforces

**Reserved namespaces.** An extension cannot register into `auth`, `command`, `debug`, `editor`,
`extension`, `file`, `language`, `scm`, `search`, `settings`, `state`, `task`, `terminal`,
`workbench` or `workspace`. Your ids live under your own namespace.

**No silent replacement.** Registering an id someone already owns is a `CONFLICT`. Replacement
is a separate, logged operation that is refused outright for commands marked sensitive —
`auth.login`, `file.delete`, `terminal.create` and the rest cannot be taken over. Use
`intercept` when you want to observe or wrap.

**No forged events.** `ctx.publish("deployed", …)` emits `robotics.deployed`. An extension cannot
raise `file.saved`.

**Failure is contained.** If activation throws, the extension is marked `FAILED`, everything it
registered is rolled back, an `extension.failed` event is published, and the IDE carries on. One
bad extension never takes the workbench down.

---

## 4. Building and installing

```xml
<dependency>
  <groupId>dev.forge</groupId>
  <artifactId>forge-core</artifactId>
  <version>0.1.0</version>
  <scope>provided</scope>
</dependency>
```

`provided`, always: the framework supplies these classes at runtime, and bundling a second copy
would give your extension its own incompatible `CommandId`.

```bash
mvn -q package
cp target/robotics-1.0.0.jar "$IDE_EXTENSIONS_DIR"
```

For Docker, mount a directory of jars:

```yaml
volumes:
  - ./extensions:/app/extensions:ro
```

Each extension gets its own class loader, so two extensions can carry different versions of the
same library without colliding.

---

## 5. Replacing a capability

Extensions add behaviour. Replacing a *capability* — where files come from, how identity is
proved, how terminals are started — is a product decision, made in `ForgeApplication`:

`FileSystem` · `WorkspaceProvider` · `AuthenticationProvider` · `TerminalProvider` ·
`LanguageProvider` · `SourceControlProvider` · `DebugAdapter` · `TaskProvider` · `StateStore`

Implement one and wire yours instead of the default. That is one line, because the composition
root is one readable file rather than a scattering of annotations.

A worked example — adding real language intelligence:

```java
languages.register(new MyLspBackedProvider("pyright-langserver", Set.of("python")));
```

`LanguageProvider` has a default for every capability, so implement only what your server
actually supports. Results from several providers are merged, so yours coexists with the
built-in buffer-word completer rather than displacing it.

---

## 6. The demo extension

[`backend/forge-ext-demo`](../backend/forge-ext-demo) is a working example kept in the
repository for a reason: it is built against `forge-core` alone and loaded from a jar, so if the
public API ever stops being sufficient, the build says so. It registers a command, a query, a
menu item and a keybinding, runs a query against the filesystem feature, and subscribes to save
events. It is around eighty lines, most of them comments.
