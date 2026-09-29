package dev.forge.filesystem;

import dev.forge.core.Args;
import dev.forge.core.RequestContext;
import dev.forge.core.command.CommandContext;
import dev.forge.core.command.CommandDescriptor;
import dev.forge.core.command.CommandRegistry;
import dev.forge.core.contrib.ContributionRegistry;
import dev.forge.core.query.QueryRegistry;
import dev.forge.core.query.QueryDescriptor;












public final class FileCommands {

    private final FileService files;

    public FileCommands(FileService files) {
        this.files = files;
    }

    public void register(CommandRegistry commands, QueryRegistry queries, ContributionRegistry contributions) {
        commands.register(
                CommandDescriptor.of("file.save", "File", "Save File") .withArguments(new dev.forge.core.command.Argument("path", "string", "path"), new dev.forge.core.command.Argument("content", "string", "content"), new dev.forge.core.command.Argument("modifiedAt", "number", "modifiedAt"), new dev.forge.core.command.Argument("revision", "string", "revision returned by file.read"))
                        .describedAs("Writes editor content to the workspace filesystem")
                        .workspaceScoped().asSensitive(),
                ctx -> files.writeText(resource(ctx), ctx.args().requiredString("content"),
                        ctx.args().longInteger("modifiedAt").orElseThrow(() ->
                                dev.forge.core.ForgeException.invalidArgument(
                                        "file.save requires the modifiedAt value returned by file.read")), ctx.args().requiredString("revision")));

        commands.register(
                CommandDescriptor.of("file.create", "File", "New File") .withArguments(new dev.forge.core.command.Argument("path", "string", "path"))
                        .workspaceScoped().asSensitive(),
                ctx -> files.createFile(resource(ctx)));

        commands.register(
                CommandDescriptor.of("file.createDirectory", "File", "New Folder") .withArguments(new dev.forge.core.command.Argument("path", "string", "path"))
                        .workspaceScoped().asSensitive(),
                ctx -> files.createDirectory(resource(ctx)));

        commands.register(
                CommandDescriptor.of("file.delete", "File", "Delete") .withArguments(new dev.forge.core.command.Argument("path", "string", "path"))
                        .describedAs("Deletes a file, or a directory when 'recursive' is set")
                        .workspaceScoped().asSensitive(),
                ctx -> {
                    files.delete(resource(ctx), ctx.args().bool("recursive", false));
                    return null;
                });

        commands.register(
                CommandDescriptor.of("file.rename", "File", "Rename") .withArguments(new dev.forge.core.command.Argument("path", "string", "path"), new dev.forge.core.command.Argument("newName", "string", "newName"))
                        .workspaceScoped().asSensitive().asUndoable(),
                ctx -> {
                    Resource from = resource(ctx);
                    return files.move(from, from.withName(ctx.args().requiredString("newName")));
                });

        commands.register(
                CommandDescriptor.of("file.move", "File", "Move") .withArguments(new dev.forge.core.command.Argument("path", "string", "path"), new dev.forge.core.command.Argument("to", "string", "to"))
                        .workspaceScoped().asSensitive().asUndoable(),
                ctx -> files.move(resource(ctx), target(ctx)));

        commands.register(
                CommandDescriptor.of("file.copy", "File", "Copy") .withArguments(new dev.forge.core.command.Argument("path", "string", "path"), new dev.forge.core.command.Argument("to", "string", "to"))
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

        contributions.addKeybinding(dev.forge.core.contrib.Keybinding.of("ctrl+s", "workbench.save", "editorFocus"));
        contributions.addMenuItem(dev.forge.core.contrib.MenuItem.of(
                ContributionRegistry.MENU_FILE, "workbench.save", "Save", "write", 10));
        contributions.addMenuItem(dev.forge.core.contrib.MenuItem.of(
                ContributionRegistry.MENU_EXPLORER_CONTEXT, "file.rename", "Rename…", "edit", 10));
        contributions.addMenuItem(dev.forge.core.contrib.MenuItem.of(
                ContributionRegistry.MENU_EXPLORER_CONTEXT, "file.delete", "Delete", "edit", 20));
    }


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
