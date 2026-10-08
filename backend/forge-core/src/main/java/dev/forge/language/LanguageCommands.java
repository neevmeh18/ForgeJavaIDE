package dev.forge.language;

import dev.forge.core.Args;
import dev.forge.core.command.CommandDescriptor;
import dev.forge.core.command.CommandRegistry;
import dev.forge.core.contrib.ContributionRegistry;
import dev.forge.core.query.QueryRegistry;
import dev.forge.core.query.QueryRegistry.QueryDescriptor;
import dev.forge.language.LanguageTypes.Position;
import dev.forge.language.LanguageTypes.Range;

/**
 * {@code language.*} queries and the rename command.
 *
 * <p>Completion, hover, definition and friends are queries: they read, they are cheap, and they
 * happen on every keystroke. Rename is a command — it produces edits that change the workspace,
 * so it belongs in the pipeline that logs, authorises and can refuse.
 */
public final class LanguageCommands {

    private final LanguageService languages;
    private final dev.forge.editor.EditorService editors;

    public LanguageCommands(LanguageService languages, dev.forge.editor.EditorService editors) {
        this.languages = languages;
        this.editors = editors;
    }

    public void register(CommandRegistry commands, QueryRegistry queries, ContributionRegistry contributions) {
        queries.register(
                QueryDescriptor.of("language.completion", "Completion proposals at a position").workspaceScoped(),
                (ctx, args) -> languages.completion(ctx.requireWorkspace(), owned(ctx, args),
                        position(args)));

        queries.register(
                QueryDescriptor.of("language.hover", "Hover information at a position").workspaceScoped(),
                (ctx, args) -> languages.hover(ctx.requireWorkspace(), owned(ctx, args),
                        position(args)).orElse(null));

        queries.register(
                QueryDescriptor.of("language.definition", "Definition locations").workspaceScoped(),
                (ctx, args) -> languages.definition(ctx.requireWorkspace(), owned(ctx, args),
                        position(args)));

        queries.register(
                QueryDescriptor.of("language.references", "Reference locations").workspaceScoped(),
                (ctx, args) -> languages.references(ctx.requireWorkspace(), owned(ctx, args),
                        position(args)));

        queries.register(
                QueryDescriptor.of("language.documentSymbols", "Symbols in one document").workspaceScoped(),
                (ctx, args) -> languages.documentSymbols(ctx.requireWorkspace(), owned(ctx, args)));

        queries.register(
                QueryDescriptor.of("language.codeActions", "Available code actions for a range").workspaceScoped(),
                (ctx, args) -> languages.codeActions(ctx.requireWorkspace(), owned(ctx, args),
                        range(args)));

        queries.register(
                QueryDescriptor.of("language.supported", "Languages with a registered provider"),
                (ctx, args) -> languages.supportedLanguages());

        commands.register(
                CommandDescriptor.of("language.rename", "Language", "Rename Symbol") .withArguments(new CommandDescriptor.Argument("documentId", "string", "documentId"), new CommandDescriptor.Argument("position", "object", "position"), new CommandDescriptor.Argument("newName", "string", "newName"))
                        .describedAs("Computes the edits that rename a symbol across the workspace")
                        .workspaceScoped(),
                ctx -> languages.rename(ctx.requireWorkspace(), owned(ctx.request(), ctx.args()),
                        position(ctx.args()), ctx.args().requiredString("newName")).orElse(null));

        contributions.addKeybinding(ContributionRegistry.Keybinding.of("f2", "workbench.renameSymbol", "editorFocus"));
    }

    private dev.forge.core.Ids.DocumentId owned(dev.forge.core.RequestContext ctx, Args args) {
        var id = args.documentId("documentId");
        editors.document(id, ctx.requireWorkspace(), ctx.sessionId());
        return id;
    }

    private static Position position(Args args) {
        Args position = args.nested("position");
        return new Position(position.integer("line", 0), position.integer("character", 0));
    }

    private static Range range(Args args) {
        Args range = args.nested("range");
        Args start = range.nested("start");
        Args end = range.nested("end");
        return new Range(
                new Position(start.integer("line", 0), start.integer("character", 0)),
                new Position(end.integer("line", 0), end.integer("character", 0)));
    }
}
