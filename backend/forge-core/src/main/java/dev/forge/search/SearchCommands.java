package dev.forge.search;

import dev.forge.core.command.CommandDescriptor;
import dev.forge.core.command.CommandRegistry;
import dev.forge.core.contrib.ContributionRegistry;








public final class SearchCommands {

    private static final int DEFAULT_LIMIT = 200;
    private static final int MAX_LIMIT = 2000;

    private final SearchService search;
    private final SavedSearchStore savedSearches;

    public SearchCommands(SearchService search, SavedSearchStore savedSearches) {
        this.search = search;
        this.savedSearches = savedSearches;
    }

    public void register(CommandRegistry commands, ContributionRegistry contributions) {
        commands.register(
                CommandDescriptor.of("search.files", "Search", "Go to File")
                        .describedAs("Finds files in the workspace by name")
                        .workspaceScoped(),
                ctx -> search.findFiles(ctx.requireWorkspace(), ctx.args().string("query").orElse(""),
                        limit(ctx.args().integer("limit", 50)), ctx.cancellation()));

        commands.register(
                CommandDescriptor.of("search.text", "Search", "Find in Files") .withArguments(new dev.forge.core.command.Argument("query", "string", "query"))
                        .describedAs("Searches file contents across the workspace")
                        .workspaceScoped(),
                ctx -> search.findText(ctx.requireWorkspace(),
                        ctx.args().requiredString("query"),
                        ctx.args().bool("regex", false),
                        ctx.args().bool("caseSensitive", false),
                        limit(ctx.args().integer("limit", DEFAULT_LIMIT)),
                        ctx.cancellation()));

        commands.register(
                CommandDescriptor.of("search.saved.save", "Search", "Save Search")
                        .withArguments(new dev.forge.core.command.Argument("query", "string", "query"))
                        .workspaceScoped(),
                ctx -> java.util.Map.of("id", savedSearches.save(ctx.requireUser(), ctx.requireWorkspace(),
                        ctx.args().requiredString("query"), ctx.args().bool("regex", false),
                        ctx.args().bool("caseSensitive", false))));

        commands.register(
                CommandDescriptor.of("search.saved.run", "Search", "Run Saved Search")
                        .withArguments(new dev.forge.core.command.Argument("id", "string", "saved search id"))
                        .workspaceScoped(),
                ctx -> {
                    dev.forge.search.SavedSearch saved = savedSearches.loadAndResolve(
                            ctx.requireUser(), ctx.requireWorkspace(), ctx.args().requiredString("id"));
                    Object result = search.findText(ctx.requireWorkspace(), saved.query(), saved.regex(),
                            saved.caseSensitive(), DEFAULT_LIMIT, ctx.cancellation());
                    return java.util.Map.of("query", saved.query(), "regex", saved.regex(),
                            "caseSensitive", saved.caseSensitive(), "result", result);
                });

        commands.register(
                CommandDescriptor.of("search.symbols", "Search", "Go to Symbol in Workspace")
                        .describedAs("Finds symbols through the workspace's language providers")
                        .workspaceScoped(),
                ctx -> search.findSymbols(ctx.requireWorkspace(), ctx.args().string("query").orElse(""),
                        ctx.cancellation()));

        contributions.addKeybinding(dev.forge.core.contrib.Keybinding.of("ctrl+p", "search.files", null));
        contributions.addKeybinding(dev.forge.core.contrib.Keybinding.of("ctrl+shift+f", "workbench.search", null));
    }

    private static int limit(int requested) {
        return Math.clamp(requested, 1, MAX_LIMIT);
    }
}
