package dev.forge.ext.demo;

import dev.forge.core.Args;
import dev.forge.core.command.CommandDescriptor;
import dev.forge.core.extension.Extension;
import dev.forge.core.extension.ExtensionContext;
import dev.forge.core.query.QueryRegistry.QueryDescriptor;
import dev.forge.filesystem.FileEvents;
import dev.forge.settings.Settings;
import dev.forge.state.StateStore;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A minimal extension, kept in the repository to keep the extension boundary honest.
 *
 * <p>It is built against {@code forge-core} alone, loaded from a jar at runtime, and reaches the
 * IDE only through {@link ExtensionContext}. It never sees {@code FileService},
 * {@code WorkspaceService} or the transport — it runs a query and registers commands, exactly as
 * the frontend or a CLI would. If this file ever needs something the public API does not offer,
 * that is a signal about the API, not a reason to widen the extension's reach.
 *
 * <p>It activates lazily: nothing is loaded until someone runs {@code demo.countFiles} or opens
 * a workspace.
 */
public final class DemoExtension implements Extension {

    private final AtomicInteger savesObserved = new AtomicInteger();

    @Override
    public void activate(ExtensionContext ctx) {
        ctx.log().info("Demo extension activating");

        // ---- Extension-owned state example ------------------------------------------------
        // Store a value on activation so we can verify persistence across sessions.
        ctx.putState(StateStore.Scope.APPLICATION, "activatedAt",
                String.valueOf(System.currentTimeMillis()));
        ctx.log().info("Stored activatedAt in extension state");

        // Command to read all state for this extension.
        ctx.registerCommand(
                CommandDescriptor.of("demo.getState", "Demo", "Get Extension State")
                        .describedAs("Returns all state stored by this extension"),
                command -> ctx.getState(StateStore.Scope.APPLICATION));

        // Command to write a state key/value.
        ctx.registerCommand(
                CommandDescriptor.of("demo.putState", "Demo", "Put Extension State Value")
                        .describedAs("Stores a value in the extension's state")
                        .withArguments(
                                new CommandDescriptor.Argument("key", "string", "key"),
                                new CommandDescriptor.Argument("value", "json", "value")),
                command -> {
                    ctx.putState(StateStore.Scope.APPLICATION,
                            command.args().requiredString("key"),
                            command.args().raw("value"));
                    return Map.of("written", command.args().requiredString("key"));
                });

        // ---- Extension-owned settings example --------------------------------------------
        // Define a setting owned by this extension.
        ctx.defineSetting("greeting", Settings.Type.STRING, "Hello from Demo!",
                "A greeting message shown by the demo extension");
        ctx.log().info("Defined demo setting 'greeting' with default value");

        // Command to read the effective value of the extension's setting.
        ctx.registerCommand(
                CommandDescriptor.of("demo.getSetting", "Demo", "Get Extension Setting")
                        .describedAs("Resolves one of this extension's settings"),
                command -> ctx.getSetting("greeting", command.request().userId(),
                        command.request().workspaceId()));

        // Command to change the setting at the user layer.
        ctx.registerCommand(
                CommandDescriptor.of("demo.setSetting", "Demo", "Set Extension Setting Value")
                        .describedAs("Overrides one of this extension's settings at the user layer")
                        .withArguments(new CommandDescriptor.Argument("value", "string", "value")),
                command -> {
                    ctx.setSetting("greeting", command.args().requiredString("value"),
                            dev.forge.settings.Settings.Layer.USER,
                            command.request().userId(), command.request().workspaceId());
                    return Map.of("set", command.args().requiredString("value"));
                });

        // ---- Existing demo functionality below -------------------------------------------

        // A command in the extension's own namespace. The framework refuses anything inside a
        // reserved namespace, so this cannot shadow a built-in.
        ctx.registerCommand(
                CommandDescriptor.of("demo.countFiles", "Demo", "Count Files in Folder")
                        .describedAs("Counts the entries in a workspace folder using the file.list query")
                        .workspaceScoped(),
                command -> {
                    Object listing = ctx.runQuery("file.list",
                            Args.of("path", command.args().string("path").orElse("")),
                            command.request());
                    int count = listing instanceof List<?> entries ? entries.size() : 0;
                    return Map.of("path", command.args().string("path").orElse(""), "entries", count);
                });

        ctx.registerQuery(
                QueryDescriptor.of("demo.savesObserved", "How many saves this extension has seen")
                        .workspaceScoped(),
                (request, args) -> Map.of("saves", savesObserved.get()));

        // Reacting to what happened elsewhere, without depending on the feature that did it.
        ctx.subscribe(FileEvents.FileSaved.class, saved -> {
            savesObserved.incrementAndGet();
            ctx.log().debug("Observed a save of " + saved.path());
        });

        ctx.contributeMenuItem("menu.view", "demo.countFiles", "Count Files", "demo", 100);
        ctx.contributeKeybinding("ctrl+alt+d", "demo.countFiles", null);
    }

    @Override
    public void deactivate() {
        // Registrations and subscriptions are released by the framework; nothing else to undo.
    }
}