package dev.forge.snapshot;

import dev.forge.core.Ids.WorkspaceId;
import dev.forge.core.command.CommandDescriptor;
import dev.forge.core.command.CommandRegistry;
import dev.forge.core.contrib.ContributionRegistry;
import dev.forge.core.query.QueryRegistry;
import dev.forge.core.query.QueryRegistry.QueryDescriptor;

/** {@code snapshot.*} commands and queries. */
public final class SnapshotCommands {

    private final SnapshotService snapshots;
    private final SnapshotSharingService sharing;

    public SnapshotCommands(SnapshotService snapshots, SnapshotSharingService sharing) {
        this.snapshots = snapshots;
        this.sharing = sharing;
    }

    public void register(CommandRegistry commands, QueryRegistry queries, ContributionRegistry contributions) {
        commands.register(
                CommandDescriptor.of("snapshot.create", "Snapshots", "Create Workspace Snapshot")
                        .workspaceScoped().asSensitive(),
                ctx -> snapshots.create(ctx.requireUser(), ctx.requireWorkspace(),
                        ctx.args().string("name").orElse("Workspace snapshot"),
                        ctx.args().strings("labels"), ctx.args().integer("retentionDays", 0)));

        commands.register(
                CommandDescriptor.of("snapshot.update", "Snapshots", "Update Snapshot Details")
                        .workspaceScoped().asSensitive(),
                ctx -> snapshots.update(ctx.requireUser(), ctx.requireWorkspace(),
                        ctx.args().requiredString("snapshotId"),
                        ctx.args().string("name").orElse("Workspace snapshot"),
                        ctx.args().strings("labels"), ctx.args().integer("retentionDays", 0)));

        commands.register(
                CommandDescriptor.of("snapshot.restore", "Snapshots", "Restore Workspace Snapshot")
                        .workspaceScoped().asSensitive(),
                ctx -> {
                    WorkspaceId current = ctx.requireWorkspace();
                    WorkspaceId target = WorkspaceId.of(ctx.args().string("targetWorkspaceId")
                            .orElse(current.value()));
                    return snapshots.restore(ctx.requireUser(), current,
                            ctx.args().requiredString("snapshotId"), target);
                });

        commands.register(
                CommandDescriptor.of("snapshot.share", "Snapshots", "Share Workspace Snapshot")
                        .workspaceScoped().asSensitive(),
                ctx -> sharing.issue(ctx.requireUser(), ctx.requireWorkspace(),
                        ctx.args().requiredString("snapshotId"),
                        ctx.args().integer("validDays", 7)));

        commands.register(
                CommandDescriptor.of("snapshot.importShared", "Snapshots", "Import Shared Snapshot")
                        .workspaceScoped().asSensitive(),
                ctx -> sharing.importSnapshot(ctx.args().requiredString("token"),
                        ctx.requireUser(), ctx.sessionId(), ctx.requireWorkspace()));

        commands.register(
                CommandDescriptor.of("snapshot.revokeShare", "Snapshots", "Revoke Snapshot Share")
                        .workspaceScoped().asSensitive(),
                ctx -> {
                    sharing.revoke(ctx.requireUser(), ctx.requireWorkspace(),
                            ctx.args().requiredString("shareId"));
                    return null;
                });

        commands.register(
                CommandDescriptor.of("snapshot.delete", "Snapshots", "Delete Workspace Snapshot")
                        .workspaceScoped().asSensitive(),
                ctx -> {
                    snapshots.delete(ctx.requireUser(), ctx.requireWorkspace(),
                            ctx.args().requiredString("snapshotId"));
                    return null;
                });

        queries.register(
                QueryDescriptor.of("snapshot.list", "Snapshots created from this workspace").workspaceScoped(),
                (ctx, args) -> snapshots.list(ctx.requireUser(), ctx.requireWorkspace()));

        queries.register(
                QueryDescriptor.of("snapshot.shares", "Active snapshot shares for this workspace")
                        .workspaceScoped(),
                (ctx, args) -> sharing.list(ctx.requireUser(), ctx.requireWorkspace()));

        queries.register(
                QueryDescriptor.of("snapshot.sharedPreview", "Details for a shared snapshot")
                        .workspaceScoped(),
                (ctx, args) -> sharing.preview(args.requiredString("token")));

        contributions.addMenuItem(ContributionRegistry.MenuItem.of(
                ContributionRegistry.MENU_FILE, "snapshot.create", "Create Snapshot…", "workspace", 30));
        contributions.addView(ContributionRegistry.View.of(
                "snapshots", "Snapshots", "sidebar", "history", 35));
    }
}
