package dev.forge.ext.demo;

import dev.forge.core.Args;
import dev.forge.core.command.CommandDescriptor;
import dev.forge.core.extension.Extension;
import dev.forge.core.extension.ExtensionContext;
import dev.forge.core.query.QueryDescriptor;
import dev.forge.filesystem.FileEvents;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;













public final class DemoExtension implements Extension {

    private final AtomicInteger savesObserved = new AtomicInteger();

    @Override
    public void activate(ExtensionContext ctx) {
        ctx.log().info("Demo extension activating");



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


        ctx.subscribe(dev.forge.filesystem.FileSaved.class, saved -> {
            savesObserved.incrementAndGet();
            ctx.log().debug("Observed a save of " + saved.path());
        });

        ctx.contributeMenuItem("menu.view", "demo.countFiles", "Count Files", "demo", 100);
        ctx.contributeKeybinding("ctrl+alt+d", "demo.countFiles", null);
    }

    @Override
    public void deactivate() {

    }
}
