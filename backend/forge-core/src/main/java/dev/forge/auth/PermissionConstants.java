package dev.forge.auth;

/**
 * String constants for the RBAC permission system.
 *
 * <p>These are the flat deny-by-default permission set. Every command that needs authorisation
 * maps to one of these constants in {@link Authorizer#COMMAND_PERMISSIONS}, and every role
 * in {@link RoleService} carries whichever of these its users should be able to exercise.
 *
 * <p>Adding a new permission constant here and wiring it into both the authorizer and the
 * role definitions is the only step needed to gate a new feature — no feature code ever
 * mentions permissions directly.
 */
public final class PermissionConstants {

    private PermissionConstants() {
    }

    public static final String FILE_SEE = "file.see";
    public static final String FILE_EDIT = "file.edit";
    public static final String FILE_CREATE = "file.create";
    public static final String FILE_DELETE = "file.delete";

    public static final String USER_ADMIN = "user.admin";
    public static final String ROLE_ADMIN = "role.admin";
    public static final String AUDIT_VIEW = "audit.view";
    public static final String SU = "su";
}