package dev.forge.editor;

import dev.forge.core.Ids.DocumentId;
import dev.forge.core.command.CommandDescriptor;
import dev.forge.core.command.CommandRegistry;
import dev.forge.core.contrib.ContributionRegistry;
import dev.forge.core.query.QueryRegistry;
import dev.forge.core.query.QueryRegistry.QueryDescriptor;
import dev.forge.filesystem.Resource;
import dev.forge.language.LanguageService;
import java.util.Optional;

/** {@code editor.*} commands and queries. */
public final class EditorCommands {

    private final EditorService editors;
    private final LanguageService languages;

    public EditorCommands(EditorService editors, LanguageService languages) {
        this.editors = editors;
        this.languages = languages;
    }

    public void register(CommandRegistry commands, QueryRegistry queries, ContributionRegistry contributions) {
        commands.register(
                CommandDescriptor.of("editor.open", "Editor", "Open File")
                        .describedAs("Opens a document buffer for a workspace file")
                        .workspaceScoped(),
                ctx -> editors.open(Resource.of(ctx.requireWorkspace(), ctx.args().requiredString("path")),
                        ctx.sessionId()));

        commands.register(
                CommandDescriptor.of("editor.close", "Editor", "Close Editor").workspaceScoped(),
                ctx -> {
                    editors.close(ctx.args().documentId("documentId"), ctx.sessionId());
                    return null;
                });

        commands.register(
                CommandDescriptor.of("editor.update", "Editor", "Update Document")
                        .describedAs("Pushes the client's working copy into the shared buffer")
                        .workspaceScoped(),
                ctx -> editors.update(ctx.args().documentId("documentId"),
                        ctx.args().requiredString("text"),
                        ctx.args().integer("version", 0)));

        queries.register(
                QueryDescriptor.of("editor.documents", "Documents open in this session").workspaceScoped(),
                (ctx, args) -> editors.documentsFor(ctx.requireWorkspace(), ctx.sessionId()));

        queries.register(
                QueryDescriptor.of("editor.document", "One open document's metadata and text").workspaceScoped(),
                (ctx, args) -> {
                    DocumentId id = args.documentId("documentId");
                    return new EditorService.OpenDocument(editors.document(id), editors.text(id));
                });

        // Formatting is surfaced as an editor action but implemented by language tooling: the
        // editor calls the language service, never the other way round.
        commands.register(
                CommandDescriptor.of("editor.format", "Editor", "Format Document")
                        .describedAs("Returns the edits a language provider would apply")
                        .workspaceScoped()
                        .availableWhen(requiresOpenDocument()),
                ctx -> languages.format(ctx.requireWorkspace(), ctx.args().documentId("documentId")));

        contributions.addKeybinding(ContributionRegistry.Keybinding.of("ctrl+w", "editor.close", "editorFocus"));
        contributions.addKeybinding(
                ContributionRegistry.Keybinding.of("ctrl+shift+i", "editor.format", "editorFocus"));
    }

    /**
     * Availability condition for commands that act on an open document — formatting, renaming a
     * symbol. Reused rather than re-expressed, so "is there a document?" means one thing.
     */
    public CommandDescriptor.Availability requiresOpenDocument() {
        return ctx -> {
            Optional<String> id = ctx.args().string("documentId");
            if (id.isEmpty()) {
                return Optional.of("No active document");
            }
            try {
                editors.document(DocumentId.of(id.get()));
                return Optional.empty();
            } catch (RuntimeException e) {
                return Optional.of("Document is not open");
            }
        };
    }
}
