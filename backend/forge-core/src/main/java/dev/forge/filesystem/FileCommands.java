package dev.forge.filesystem;

import dev.forge.core.Args;
import dev.forge.core.RequestContext;
import dev.forge.core.command.CommandContext;
import dev.forge.core.command.CommandDescriptor;
import dev.forge.core.command.CommandRegistry;
import dev.forge.core.contrib.ContributionRegistry;
import dev.forge.core.query.QueryRegistry;
import dev.forge.core.query.QueryRegistry.QueryDescriptor;

/**
 * The filesystem feature's public surface: {@code file.*} commands and queries.
 *
 * <p>Everything here is a thin intent that delegates straight to {@link FileService}. Splitting
 * each of these into a command class, a request, a response, a validator and a mapper would
 * turn one readable file into thirty-five, and would not make {@code file.rename} any clearer.
 *
 * <p>All of them are marked sensitive: writing to a workspace is a privileged operation, so no
 * extension may take the id over, and every one re-derives the workspace from the caller's
 * context rather than trusting an id supplied by the frontend.
 */
public final class FileCommands {

    private final FileService files;

    public FileCommands(FileService files) {
        this.files = files;
    }

    public void register(CommandRegistry commands, QueryRegistry queries, ContributionRegistry contributions) {
        commands.register(
                CommandDescriptor.of("file.save", "File", "Save File")
                        .describedAs("Writes editor content to the workspace filesystem")
                        .workspaceScoped().asSensitive(),
                ctx -> files.writeText(resource(ctx), ctx.args().requiredString("content")));

        commands.register(
                CommandDescriptor.of("file.create", "File", "New File")
                        .workspaceScoped().asSensitive(),
                ctx -> files.createFile(resource(ctx)));

        commands.register(
                CommandDescriptor.of("file.createDirectory", "File", "New Folder")
                        .workspaceScoped().asSensitive(),
                ctx -> files.createDirectory(resource(ctx)));

        commands.register(
                CommandDescriptor.of("file.delete", "File", "Delete")
                        .describedAs("Deletes a file, or a directory when 'recursive' is set")
                        .workspaceScoped().asSensitive(),
                ctx -> {
                    files.delete(resource(ctx), ctx.args().bool("recursive", false));
                    return null;
                });

        commands.register(
                CommandDescriptor.of("file.rename", "File", "Rename")
                        .workspaceScoped().asSensitive().asUndoable(),
                ctx -> {
                    Resource from = resource(ctx);
                    return files.move(from, from.withName(ctx.args().requiredString("newName")));
                });

        commands.register(
                CommandDescriptor.of("file.move", "File", "Move")
                        .workspaceScoped().asSensitive().asUndoable(),
                ctx -> files.move(resource(ctx), target(ctx)));

        commands.register(
                CommandDescriptor.of("file.copy", "File", "Copy")
                        .workspaceScoped().asSensitive(),
                ctx -> files.copy(resource(ctx), target(ctx)));

        queries.register(
                QueryDescriptor.of("file.list", "Directory children").workspaceScoped(),
                (ctx, args) -> files.list(resource(ctx, args)));

        queries.register(
                QueryDescriptor.of("file.read", "File content as text").workspaceScoped(),
                (ctx, args) -> files.readText(resource(ctx, args)));

        queries.register(
                QueryDescriptor.of("file.stat", "File metadata").workspaceScoped(),
                (ctx, args) -> files.stat(resource(ctx, args)));

        contributions.addKeybinding(ContributionRegistry.Keybinding.of("ctrl+s", "file.save", "editorFocus"));
        contributions.addMenuItem(ContributionRegistry.MenuItem.of(
                ContributionRegistry.MENU_FILE, "file.save", "Save", "write", 10));
        contributions.addMenuItem(ContributionRegistry.MenuItem.of(
                ContributionRegistry.MENU_EXPLORER_CONTEXT, "file.rename", "Rename…", "edit", 10));
        contributions.addMenuItem(ContributionRegistry.MenuItem.of(
                ContributionRegistry.MENU_EXPLORER_CONTEXT, "file.delete", "Delete", "edit", 20));
    }

    /** The workspace always comes from the authenticated context, never from the arguments. */
    private static Resource resource(CommandContext ctx) {
        return Resource.of(ctx.requireWorkspace(), ctx.args().requiredString("path"));
    }

    private static Resource target(CommandContext ctx) {
        return Resource.of(ctx.requireWorkspace(), ctx.args().requiredString("to"));
    }

    private static Resource resource(RequestContext ctx, Args args) {
        return Resource.of(ctx.requireWorkspace(), args.string("path").orElse(""));
    }
}
