package dev.forge.auth;

import java.util.Collections;
import java.util.Set;

/**
 * A named role with its associated permission set.
 *
 * <p>Roles are immutable after creation. Changing a role's permissions produces a new
 * {@code Role} instance; the {@link RoleService} replaces the old one atomically.
 *
 * @param name         the stable, machine-readable identifier (e.g. {@code "admin"})
 * @param displayName  a human-readable label (e.g. {@code "Administrator"})
 * @param permissions  the set of permission constants this role grants
 */
public record Role(String name, String displayName, Set<String> permissions) {

    public Role {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Role name must not be blank");
        }
        displayName = displayName == null || displayName.isBlank() ? name : displayName;
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }

    /**
     * Returns whether this role has the given permission.
     */
    public boolean has(String permission) {
        return permissions.contains(permission);
    }

    /**
     * Returns a new role with the specified set of permissions, keeping the same name.
     */
    public Role withPermissions(Set<String> newPermissions) {
        return new Role(name, displayName, newPermissions);
    }
}