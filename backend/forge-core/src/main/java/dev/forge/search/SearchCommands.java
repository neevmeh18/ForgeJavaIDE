package dev.forge.search;

import dev.forge.core.command.CommandDescriptor;
import dev.forge.core.command.CommandRegistry;
import dev.forge.core.contrib.ContributionRegistry;

/**
 * {@code search.*} commands.
 *
 * <p>Search is a command rather than a query even though it reads nothing but files: it is
 * long-running and must be cancellable, and those are exactly the properties the command
 * pipeline provides. Cheap reads stay queries; expensive work becomes a command.
 */
public final class SearchCommands {

    private static final int DEFAULT_LIMIT = 200;
    private static final int MAX_LIMIT = 2000;

    private final SearchService search;

    public SearchCommands(SearchService search) {
        this.search = search;
    }

    public void register(CommandRegistry commands, ContributionRegistry contributions) {
        commands.register(
                CommandDescriptor.of("search.files", "Search", "Go to File")
                        .describedAs("Finds files in the workspace by name")
                        .workspaceScoped(),
                ctx -> search.findFiles(ctx.requireWorkspace(), ctx.args().string("query").orElse(""),
                        limit(ctx.args().integer("limit", 50)), ctx.cancellation()));

        commands.register(
                CommandDescriptor.of("search.text", "Search", "Find in Files") .withArguments(new CommandDescriptor.Argument("query", "string", "query"))
                        .describedAs("Searches file contents across the workspace")
                        .workspaceScoped(),
                ctx -> search.findText(ctx.requireWorkspace(),
                        ctx.args().requiredString("query"),
                        ctx.args().bool("regex", false),
                        ctx.args().bool("caseSensitive", false),
                        limit(ctx.args().integer("limit", DEFAULT_LIMIT)),
                        ctx.cancellation()));

        commands.register(
                CommandDescriptor.of("search.symbols", "Search", "Go to Symbol in Workspace")
                        .describedAs("Finds symbols through the workspace's language providers")
                        .workspaceScoped(),
                ctx -> search.findSymbols(ctx.requireWorkspace(), ctx.args().string("query").orElse(""),
                        ctx.cancellation()));

        contributions.addKeybinding(ContributionRegistry.Keybinding.of("ctrl+p", "search.files", null));
        contributions.addKeybinding(ContributionRegistry.Keybinding.of("ctrl+shift+f", "workbench.search", null));
    }

    private static int limit(int requested) {
        return Math.clamp(requested, 1, MAX_LIMIT);
    }
}
