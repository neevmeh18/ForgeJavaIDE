package dev.forge.app;

import dev.forge.core.command.CommandDescriptor;
import dev.forge.core.command.CommandRegistry;
import dev.forge.core.query.QueryRegistry;
import dev.forge.core.query.QueryRegistry.QueryDescriptor;

public final class BackupCommands {
    private final BackupService backups;

    public BackupCommands(BackupService backups) {
        this.backups = backups;
    }

    public void register(CommandRegistry commands, QueryRegistry queries) {
        commands.register(
                CommandDescriptor.of("backup.configure", "Backup", "Save Backup Configuration")
                        .workspaceScoped().asSensitive()
                        .withArguments(new CommandDescriptor.Argument("destination", "string", "Destination")),
                ctx -> backups.configure(ctx.requireUser(), ctx.requireWorkspace(),
                        ctx.args().requiredString("destination")));

        commands.register(
                CommandDescriptor.of("backup.run", "Backup", "Run Backup")
                        .workspaceScoped().asSensitive(),
                ctx -> backups.run(ctx.requireUser(), ctx.requireWorkspace()));

        queries.register(
                QueryDescriptor.of("backup.configuration", "Backup configuration").workspaceScoped(),
                (ctx, args) -> backups.configuration(ctx.requireUser(), ctx.requireWorkspace()));
    }
}
