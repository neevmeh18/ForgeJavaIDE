package dev.forge.auth;

import dev.forge.core.ForgeException;
import dev.forge.core.RequestContext;
import dev.forge.core.command.CommandContext;
import dev.forge.core.command.CommandDescriptor;
import dev.forge.core.command.CommandExecutor;
import dev.forge.core.query.QueryRegistry;
import dev.forge.workspace.WorkspaceService;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The single authorisation decision point for all commands and queries.
 *
 * <p>Authorization is deny-by-default: every command and query is checked against the role's
 * permitted set. A command without a declared permission requirement (not listed in
 * {@link #COMMAND_PERMISSIONS}) is allowed; once a permission is listed, the session's role
 * must grant it or the request is rejected with {@code FORBIDDEN}.
 *
 * <p>Workspace access controls are also enforced here: a session may only act on workspaces
 * it is attached to. This prevents workspace-id guessing attacks.
 */
public final class Authorizer implements CommandExecutor.Authorizer, QueryRegistry.Authorizer {

    /**
     * Maps command IDs to the single permission constant they require.
     * Only commands listed here are RBAC-gated; unlisted commands require only a session.
     */
    static final Map<String, String> COMMAND_PERMISSIONS = Map.ofEntries(
            // File reading
            Map.entry("file.list", PermissionConstants.FILE_SEE),
            Map.entry("file.read", PermissionConstants.FILE_SEE),
            Map.entry("file.stat", PermissionConstants.FILE_SEE),
            // File editing
            Map.entry("file.save", PermissionConstants.FILE_EDIT),
            Map.entry("file.rename", PermissionConstants.FILE_EDIT),
            Map.entry("file.move", PermissionConstants.FILE_EDIT),
            // File creation
            Map.entry("file.create", PermissionConstants.FILE_CREATE),
            Map.entry("file.createDirectory", PermissionConstants.FILE_CREATE),
            Map.entry("file.copy", PermissionConstants.FILE_CREATE),
            // File deletion
            Map.entry("file.delete", PermissionConstants.FILE_DELETE),
            // User administration
            Map.entry("auth.useradd", PermissionConstants.USER_ADMIN),
            Map.entry("auth.userdel", PermissionConstants.USER_ADMIN),
            Map.entry("auth.usermod", PermissionConstants.USER_ADMIN),
            Map.entry("auth.users", PermissionConstants.USER_ADMIN),
            // Role administration
            Map.entry("auth.chmod", PermissionConstants.ROLE_ADMIN),
            Map.entry("auth.roles", PermissionConstants.ROLE_ADMIN),
            // Audit
            Map.entry("audit.log", PermissionConstants.AUDIT_VIEW),
            // Superuser
            Map.entry("auth.su", PermissionConstants.SU)
    );

    private static final Set<String> WILDCARD = Set.of("*");

    private final WorkspaceService workspaces;
    private final SessionService sessions;
    private final RoleService roles;

    public Authorizer(WorkspaceService workspaces, SessionService sessions, RoleService roles) {
        this.workspaces = workspaces;
        this.sessions = sessions;
        this.roles = roles;
    }

    @Override
    public void authorize(CommandDescriptor descriptor, CommandContext ctx) {
        checkWorkspaceAccess(ctx.request(), descriptor.requiresWorkspace());
        checkRbac(descriptor.id().value(), ctx.request());
    }

    @Override
    public void authorize(QueryRegistry.QueryDescriptor descriptor, RequestContext ctx) {
        checkWorkspaceAccess(ctx, descriptor.requiresWorkspace());
        checkRbac(descriptor.id(), ctx);
    }

    /**
     * RBAC check: resolves the session's role, looks up the role's permissions, and verifies
     * the required permission (if any) is granted. System-origin requests bypass RBAC.
     */
    private void checkRbac(String commandId, RequestContext ctx) {
        if (ctx.origin() == RequestContext.Origin.SYSTEM) {
            return;
        }
        String required = COMMAND_PERMISSIONS.get(commandId);
        if (required == null) {
            return; // No RBAC requirement for this command
        }
        if (ctx.sessionId() == null) {
            throw ForgeException.unauthorized("Authentication required");
        }
        String roleName = sessions.roleOf(ctx.sessionId()).orElse("guest");
        try {
            Set<String> granted = roles.permissionsOfRole(roleName);
            if (granted.contains(required) || granted.contains("*")) {
                return;
            }
        } catch (ForgeException e) {
            // Unknown role — fall through to deny
        }
        throw ForgeException.forbidden("Missing permission: " + required)
                .with("commandId", commandId);
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