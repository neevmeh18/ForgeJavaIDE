package dev.forge.auth;

import dev.forge.core.ForgeException;
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
 * <p>A session may only act on a workspace it is attached to. Inside a workspace, anyone who is
 * not its owner is read-only: they may run the commands and queries listed below and nothing
 * else, so a new command or query is owner-only until it is added here. Outside a workspace, a
 * guest may only open their workspace and sign out; workspace lists stay hidden from them. The
 * frontend chooses the workspace header on every request and is never trusted for any check.
 *
 * <p>Features stay free of authorisation code, and no feature can accidentally skip it, because
 * the executor consults this before any handler runs.
 */
public final class Authorizer implements CommandExecutor.Authorizer, QueryRegistry.Authorizer {

    /** What a viewer may run in a workspace: opening files and searching, nothing that changes it. */
    private static final Set<String> VIEWER_COMMANDS = Set.of(
            "editor.open", "editor.close", "search.files", "search.text", "search.symbols");

    /** What a viewer may read in a workspace: files, language data and history. No terminal or debug data. */
    private static final Set<String> VIEWER_QUERIES = Set.of(
            "workspace.current", "file.list", "file.read", "file.stat", "editor.document", "editor.documents",
            "language.supported", "language.hover", "language.completion", "language.definition",
            "language.references", "language.documentSymbols", "scm.status", "scm.diff", "scm.history",
            "scm.branches");

    /** What a guest may run outside a workspace. Opening is checked again by the workspace feature. */
    private static final Set<String> GUEST_COMMANDS = Set.of("workspace.open", "auth.logout", "command.cancel");

    /** What a guest may read outside a workspace. */
    private static final Set<String> GUEST_QUERIES = Set.of("auth.currentUser", "workbench.commands",
            "workbench.contributions", "workbench.status");

    private final WorkspaceService workspaces;
    private final WorkspaceAccessService access;

    public Authorizer(WorkspaceService workspaces, WorkspaceAccessService access) {
        this.workspaces = workspaces;
        this.access = access;
    }

    @Override
    public void authorize(CommandDescriptor descriptor, CommandContext ctx) {
        checkWorkspaceAccess(ctx.request(), descriptor.requiresWorkspace());
        checkRole(ctx.request(), descriptor.id().value(), VIEWER_COMMANDS, GUEST_COMMANDS);
    }

    @Override
    public void authorize(QueryRegistry.QueryDescriptor descriptor, RequestContext ctx) {
        checkWorkspaceAccess(ctx, descriptor.requiresWorkspace());
        checkRole(ctx, descriptor.id(), VIEWER_QUERIES, GUEST_QUERIES);
    }

    private void checkRole(RequestContext ctx, String id, Set<String> viewerAllowed, Set<String> guestAllowed) {
        if (ctx.origin() == RequestContext.Origin.SYSTEM) {
            return;
        }
        if (ctx.workspaceId() != null) {
            if (ctx.sessionId() == null) {
                throw ForgeException.unauthorized("Workspace access requires a session");
            }
            if (!access.isOwner(ctx.sessionId(), ctx.workspaceId()) && !viewerAllowed.contains(id)) {
                throw ForgeException.forbidden("Workspace role does not allow this");
            }
            return;
        }
        if (access.isGuest(ctx.userId()) && !guestAllowed.contains(id)) {
            throw ForgeException.forbidden("Guests can only work inside the workspace they were invited to");
        }
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
