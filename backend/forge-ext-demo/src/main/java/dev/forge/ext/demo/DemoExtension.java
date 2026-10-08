package dev.forge.ext.demo;

import dev.forge.core.Args;
import dev.forge.core.command.CommandDescriptor;
import dev.forge.core.extension.Extension;
import dev.forge.core.extension.ExtensionContext;
import dev.forge.core.query.QueryRegistry.QueryDescriptor;
import dev.forge.filesystem.FileEvents;
import dev.forge.settings.Settings;
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

        // Durable per-extension state: this survives restarts, is namespaced to this
        // extension alone, and needs no user or workspace context to read back.
        long launchCount = ctx.getState("launchCount") instanceof Number n ? n.longValue() + 1 : 1;
        ctx.setState("launchCount", launchCount);
        ctx.log().with("launchCount", launchCount).info("Demo extension has been activated before");

        // A typed, user-overridable setting contributed by this extension. Deactivation
        // removes the declaration automatically.
        if (ctx.getSetting("demo.showSaves") == null) {
            ctx.defineSetting(Settings.Definition.of(
                    "demo.showSaves", Settings.Type.BOOLEAN, true, "Report file saves to the log"));
            ctx.log().info("Defined demo.showSaves setting");
        }

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
