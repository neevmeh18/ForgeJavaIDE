package dev.forge.auth;

import dev.forge.core.ForgeException;
import dev.forge.core.Ids.UserId;
import dev.forge.core.RequestContext;
import dev.forge.core.command.CommandContext;
import dev.forge.core.command.CommandDescriptor;
import dev.forge.core.command.CommandExecutor;
import dev.forge.core.query.QueryRegistry;
import dev.forge.workspace.WorkspaceAccessService;
import dev.forge.workspace.WorkspaceService;
import java.util.Set;

/**
 * The single authorisation decision point for commands and queries.
 *
 * <p>A session may only act on a workspace it is attached to. Sensitive commands additionally
 * require the owner role for that workspace: file writes, source-control mutations, tasks and
 * terminals are owner operations, while queries stay available to every attached role so a
 * viewer can browse the project. The frontend chooses the workspace header on every request
 * and is never trusted for either check.
 *
 * <p>Features stay free of authorisation code, and no feature can accidentally skip it, because
 * the executor consults this before any handler runs.
 */
public final class Authorizer implements CommandExecutor.Authorizer, QueryRegistry.Authorizer {

    /**
     * Sensitive commands that are not tied to one workspace. Opening is checked again by the
     * workspace feature, which keeps an existing guest role instead of granting a new one.
     * Signing out has to remain available to every session.
     */
    private static final Set<String> GUEST_SESSION_COMMANDS = Set.of("workspace.open", "auth.logout");

    private final WorkspaceService workspaces;
    private final WorkspaceAccessService access;

    public Authorizer(WorkspaceService workspaces, WorkspaceAccessService access) {
        this.workspaces = workspaces;
        this.access = access;
    }

    @Override
    public void authorize(CommandDescriptor descriptor, CommandContext ctx) {
        checkWorkspaceAccess(ctx.request(), descriptor.requiresWorkspace());
        if (!descriptor.sensitive() || ctx.request().origin() == RequestContext.Origin.SYSTEM) {
            return;
        }
        if (ctx.workspaceId() != null) {
            if (!access.isOwner(ctx.sessionId(), ctx.workspaceId())) {
                throw ForgeException.forbidden("Workspace role does not allow this command");
            }
            return;
        }
        UserId user = ctx.userId();
        if (user != null && access.isGuest(user) && !GUEST_SESSION_COMMANDS.contains(descriptor.id().value())) {
            throw ForgeException.forbidden("Workspace role does not allow this command");
        }
    }

    @Override
    public void authorize(QueryRegistry.QueryDescriptor descriptor, RequestContext ctx) {
        checkWorkspaceAccess(ctx, descriptor.requiresWorkspace());
    }

    private void checkWorkspaceAccess(RequestContext ctx, boolean required) {
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
