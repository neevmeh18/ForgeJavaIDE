package dev.forge.infra;

import dev.forge.auth.PermissionConstants;
import dev.forge.auth.Role;
import dev.forge.auth.RoleService;
import dev.forge.core.ForgeException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The default role service with four built-in roles and support for custom roles from config.
 *
 * <p>Built-in roles are immutable and cannot be deleted. Custom roles may be added, modified,
 * or removed at runtime via the {@link RoleService} interface.
 *
 * <p>Unknown roles fall back to the {@code guest} role, which has the narrowest permission set.
 * This ensures that even if a user's role file is corrupted or a role is deleted, they still
 * have the minimal permission set rather than none.
 */
public final class DefaultRoleService implements RoleService {

    private static final Role GUEST = new Role("guest", "Guest",
            Set.of(PermissionConstants.FILE_SEE));

    private static final Role DEVELOPER = new Role("developer", "Developer",
            Set.of(PermissionConstants.FILE_SEE, PermissionConstants.FILE_EDIT,
                    PermissionConstants.FILE_CREATE));

    private static final Role ADMIN = new Role("admin", "Administrator",
            Set.of(PermissionConstants.FILE_SEE, PermissionConstants.FILE_EDIT,
                    PermissionConstants.FILE_CREATE, PermissionConstants.FILE_DELETE,
                    PermissionConstants.USER_ADMIN));

    private static final Role SUPERADMIN = new Role("superadmin", "Super Administrator",
            Set.of("*"));

    private static final Set<String> BUILTIN_NAMES = Set.of(
            GUEST.name(), DEVELOPER.name(), ADMIN.name(), SUPERADMIN.name());

    private static final Map<String, Role> BUILTINS = Map.of(
            GUEST.name(), GUEST,
            DEVELOPER.name(), DEVELOPER,
            ADMIN.name(), ADMIN,
            SUPERADMIN.name(), SUPERADMIN);

    private final ConcurrentHashMap<String, Role> customRoles = new ConcurrentHashMap<>();

    public DefaultRoleService() {
        // No custom roles by default
    }

    /**
     * Creates a role service with extra custom roles merged from a configuration map.
     *
     * @param configRoles map of role name → list of permission strings
     */
    public DefaultRoleService(Map<String, List<String>> configRoles) {
        if (configRoles != null) {
            for (var entry : configRoles.entrySet()) {
                String name = entry.getKey();
                if (BUILTIN_NAMES.contains(name)) {
                    continue; // Skip built-in names
                }
                customRoles.put(name, new Role(name, name, Set.copyOf(entry.getValue())));
            }
        }
    }

    @Override
    public Role role(String name) {
        if (name == null) {
            return GUEST;
        }
        Role builtin = BUILTINS.get(name);
        if (builtin != null) {
            return builtin;
        }
        Role custom = customRoles.get(name);
        if (custom != null) {
            return custom;
        }
        return GUEST; // Fall back to guest for unknown roles
    }

    @Override
    public List<Role> listRoles() {
        List<Role> all = new java.util.ArrayList<>();
        all.addAll(BUILTINS.values());
        all.addAll(customRoles.values());
        return Collections.unmodifiableList(all);
    }

    @Override
    public void createRole(Role role) {
        if (role == null || role.name() == null || role.name().isBlank()) {
            throw ForgeException.invalidArgument("Role name must not be blank");
        }
        if (BUILTIN_NAMES.contains(role.name())) {
            throw ForgeException.conflict("Cannot create a role with a built-in name: " + role.name());
        }
        if (customRoles.putIfAbsent(role.name(), role) != null) {
            throw ForgeException.conflict("Role already exists: " + role.name());
        }
    }

    @Override
    public Role updatePermissions(String name, Set<String> permissions) {
        if (BUILTIN_NAMES.contains(name)) {
            throw ForgeException.forbidden("Cannot modify built-in role permissions: " + name);
        }
        Role existing = customRoles.get(name);
        if (existing == null) {
            throw ForgeException.notFound("Role not found: " + name);
        }
        Role updated = new Role(name, existing.displayName(), Set.copyOf(permissions));
        customRoles.put(name, updated);
        return updated;
    }

    @Override
    public void deleteRole(String name) {
        if (BUILTIN_NAMES.contains(name)) {
            throw ForgeException.forbidden("Cannot delete built-in role: " + name);
        }
        if (customRoles.remove(name) == null) {
            throw ForgeException.notFound("Role not found: " + name);
        }
    }

    @Override
    public Set<String> permissionsOfRole(String name) {
        return role(name).permissions();
    }
}