package dev.forge.auth;

import dev.forge.core.ForgeException;
import dev.forge.core.RequestContext;
import dev.forge.core.command.CommandContext;
import dev.forge.core.command.CommandDescriptor;
import dev.forge.core.command.CommandExecutor;
import dev.forge.core.query.QueryRegistry;
import dev.forge.workspace.WorkspaceService;

/**
 * The single authorisation decision point for commands and queries.
 *
 * <p>Today's policy is deliberately small — there are no roles yet — but it is not empty: a
 * session may only act on a workspace it is actually attached to. Without that check, knowing a
 * workspace id would be enough to read someone else's files, since the frontend chooses the
 * workspace header on every request and the frontend is never trusted.
 *
 * <p>When roles, organisations or per-workspace permissions arrive, they arrive here. Features
 * stay free of authorisation code, and no feature can accidentally skip it, because the
 * executor consults this before any handler runs.
 */
public final class Authorizer implements CommandExecutor.Authorizer, QueryRegistry.Authorizer {

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
    public void authorize(QueryRegistry.QueryDescriptor descriptor, RequestContext ctx) {
        checkWorkspaceAccess(ctx, descriptor.requiresWorkspace());
    }

    private void checkWorkspaceAccess(RequestContext ctx, boolean required) {
        if (ctx.origin() != RequestContext.Origin.SYSTEM && ctx.sessionId() != null && sessions.find(ctx.sessionId()).isEmpty())
            throw ForgeException.unauthorized("Session is no longer active");
        if (ctx.workspaceId() != null && ctx.sessionId() == null && ctx.origin() != RequestContext.Origin.SYSTEM)
            throw ForgeException.unauthorized("Workspace access requires a session");
        if (ctx.workspaceId() == null) {
            if (required) {
                throw ForgeException.invalidArgument("No workspace selected");
            }
            return;
        }
        if (ctx.origin() == RequestContext.Origin.SYSTEM) {
            return;
        }
        // Throws NOT_FOUND if the workspace is not open, FORBIDDEN if this session never
        // attached to it. Both are correct answers to "may I touch this workspace?".
        workspaces.require(ctx.workspaceId(), ctx.sessionId());
    }
}
