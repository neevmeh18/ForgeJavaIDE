package dev.forge.auth;

import dev.forge.core.Ids.UserId;
import java.time.Instant;

/**
 * The persistent representation of a user account.
 *
 * <p>This record is what gets serialised to disk by {@link dev.forge.infra.FileUserStore}.
 * It carries the PBKDF2 hash and salt — never the raw password — plus the user's role and
 * account status. The {@link User} record returned at authentication time is a separate,
 * transient value that never contains the hash.
 *
 * @param id           the framework user id
 * @param username     the login name the user types
 * @param passwordHash Base64-encoded PBKDF2 hash of the password
 * @param salt         Base64-encoded random salt used during hashing
 * @param role         the assigned role name (default {@code "developer"})
 * @param enabled      whether this account may log in (default {@code true})
 * @param createdAt    when the account was created
 * @param updatedAt    when the account was last modified
 */
public record StoredUser(
        UserId id,
        String username,
        String passwordHash,
        String salt,
        String role,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt) {

    public static final String DEFAULT_ROLE = "developer";

    public StoredUser {
        if (id == null) {
            throw new IllegalArgumentException("User id must not be null");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Username must not be blank");
        }
        if (passwordHash == null || passwordHash.isBlank()) {
            throw new IllegalArgumentException("Password hash must not be blank");
        }
        if (salt == null || salt.isBlank()) {
            throw new IllegalArgumentException("Salt must not be blank");
        }
        role = role == null || role.isBlank() ? DEFAULT_ROLE : role;
        createdAt = createdAt == null ? Instant.now() : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }

    /**
     * Convenience factory for creating a new user with sensible defaults.
     */
    public static StoredUser of(UserId id, String username, String passwordHash, String salt, String role) {
        Instant now = Instant.now();
        return new StoredUser(id, username, passwordHash, salt, role, true, now, now);
    }

    /**
     * Returns a copy with a new password hash and salt, and an updated timestamp.
     */
    public StoredUser withPassword(String newHash, String newSalt) {
        return new StoredUser(id, username, newHash, newSalt, role, enabled, createdAt, Instant.now());
    }

    /**
     * Returns a copy with a new role.
     */
    public StoredUser withRole(String newRole) {
        return new StoredUser(id, username, passwordHash, salt, newRole, enabled, createdAt, Instant.now());
    }

    /**
     * Returns a copy with a new enabled status.
     */
    public StoredUser withEnabled(boolean enabled) {
        return new StoredUser(id, username, passwordHash, salt, role, enabled, createdAt, Instant.now());
    }
}