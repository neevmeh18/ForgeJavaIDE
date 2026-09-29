package dev.forge.auth;

import dev.forge.core.ForgeException;
import dev.forge.core.RequestContext;
import dev.forge.core.command.CommandContext;
import dev.forge.core.command.CommandDescriptor;
import dev.forge.core.command.CommandExecutor;
import dev.forge.core.query.QueryRegistry;
import dev.forge.workspace.WorkspaceService;













public final class Authorizer implements dev.forge.core.command.Authorizer, dev.forge.core.query.Authorizer {

    private final WorkspaceService workspaces;
    private final SessionService sessions;

    public Authorizer(WorkspaceService workspaces, SessionService sessions) {
        this.workspaces = workspaces;
        this.sessions = sessions;
    }

    @Override
    public void authorize(CommandDescriptor descriptor, CommandContext ctx) {
        checkWorkspaceAccess(ctx.request(), descriptor.requiresWorkspace());
    }

    @Override
    public void authorize(dev.forge.core.query.QueryDescriptor descriptor, RequestContext ctx) {
        checkWorkspaceAccess(ctx, descriptor.requiresWorkspace());
    }

    private void checkWorkspaceAccess(RequestContext ctx, boolean required) {
        if (ctx.origin() != dev.forge.core.Origin.SYSTEM && ctx.sessionId() != null && sessions.find(ctx.sessionId()).isEmpty())
            throw ForgeException.unauthorized("Session is no longer active");
        if (ctx.workspaceId() != null && ctx.sessionId() == null && ctx.origin() != dev.forge.core.Origin.SYSTEM)
            throw ForgeException.unauthorized("Workspace access requires a session");
        if (ctx.workspaceId() == null) {
            if (required) {
                throw ForgeException.invalidArgument("No workspace selected");
            }
            return;
        }
        if (ctx.origin() == dev.forge.core.Origin.SYSTEM) {
            return;
        }


        workspaces.require(ctx.workspaceId(), ctx.sessionId());
    }
}
