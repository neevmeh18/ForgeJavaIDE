package dev.forge.auth;

import java.util.List;
import java.util.Set;

/**
 * Service contract for role definitions and permission management.
 *
 * <p>Every role carries a set of permission strings (see {@link PermissionConstants}) that
 * the {@link Authorizer} checks before allowing a command. Roles are additive only: denying
 * a permission means not granting it, not creating a negative grant.
 */
public interface RoleService {

    /**
     * Returns the named role, or throws {@link dev.forge.core.ForgeException#notFound}.
     */
    Role role(String name);

    /**
     * Returns every defined role.
     */
    List<Role> listRoles();

    /**
     * Creates a new role. Throws {@link dev.forge.core.ForgeException#conflict} if the
     * name already exists.
     */
    void createRole(Role role);

    /**
     * Replaces the permission set of an existing role. Throws
     * {@link dev.forge.core.ForgeException#notFound} if the role does not exist.
     *
     * @return the updated role
     */
    Role updatePermissions(String name, Set<String> permissions);

    /**
     * Removes a role. Throws {@link dev.forge.core.ForgeException#notFound} if the
     * role does not exist.
     */
    void deleteRole(String name);

    /**
     * Returns the set of permissions granted by the named role. Throws
     * {@link dev.forge.core.ForgeException#notFound} if the role does not exist.
     */
    Set<String> permissionsOfRole(String name);
}