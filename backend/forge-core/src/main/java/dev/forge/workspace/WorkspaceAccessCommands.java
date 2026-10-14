package dev.forge.workspace;

import dev.forge.core.ForgeException;
import dev.forge.core.command.CommandDescriptor;
import dev.forge.core.command.CommandRegistry;
import dev.forge.core.contrib.ContributionRegistry;
import dev.forge.core.query.QueryRegistry;
import dev.forge.core.query.QueryRegistry.QueryDescriptor;
import dev.forge.workspace.WorkspaceAccessService.Role;

/** {@code workspaceAccess.*} commands and queries. */
public final class WorkspaceAccessCommands {

    private final WorkspaceAccessService access;

    public WorkspaceAccessCommands(WorkspaceAccessService access) {
        this.access = access;
    }

    public void register(CommandRegistry commands, QueryRegistry queries, ContributionRegistry contributions) {
        commands.register(
                CommandDescriptor.of("workspaceAccess.invite", "Workspace", "Invite to Workspace")
                        .describedAs("Creates an expiring invitation for the current workspace")
                        .workspaceScoped().asSensitive(),
                ctx -> access.invite(
                        ctx.requireUser(),
                        ctx.requireWorkspace(),
                        role(ctx.args().requiredString("role")),
                        ctx.args().integer("validDays", 7)));

        commands.register(
                CommandDescriptor.of("workspaceAccess.revoke", "Workspace", "Revoke Invitation")
                        .workspaceScoped().asSensitive(),
                ctx -> {
                    access.revoke(ctx.requireWorkspace(), ctx.args().requiredString("invitationId"));
                    return null;
                });

        commands.register(
                CommandDescriptor.of("workspaceAccess.accept", "Workspace", "Accept Invitation")
                        .describedAs("Exchanges an invitation token for a guest session")
                        .anonymous().asSensitive(),
                ctx -> access.accept(
                        ctx.args().requiredString("token"),
                        role(ctx.args().requiredString("role")),
                        ctx.args().string("client").orElse("")));

        queries.register(
                QueryDescriptor.of("workspaceAccess.list", "Open invitations for the current workspace")
                        .workspaceScoped(),
                (ctx, args) -> access.list(ctx.requireWorkspace()));

        queries.register(
                QueryDescriptor.of("workspaceAccess.preview", "Invitation details for a token").anonymous(),
                (ctx, args) -> access.preview(args.requiredString("token")));

        contributions.addView(ContributionRegistry.View.of(
                "access", "Access", "sidebar", "users", 35));
    }

    private static Role role(String value) {
        try {
            return Role.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw ForgeException.invalidArgument("Invalid workspace role");
        }
    }
}
