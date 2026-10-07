package dev.forge.auth;

import java.util.List;
import java.util.Optional;

/**
 * Persistent user account storage.
 *
 * <p>Separated from {@link AuthenticationProvider} so that providers focus on credential
 * verification alone and user management lives behind its own interface. Implementations
 * store passwords pre-hashed; hashing is the provider's responsibility.
 */
public interface UserStore {

    /** A stored user account. The {@code passwordHash} is provider-dependent. */
    record StoredUser(
            String id,
            String username,
            String displayName,
            String passwordHash,
            java.util.Map<String, String> attributes,
            long createdAtEpochMillis) {
    }

    Optional<StoredUser> findByUsername(String username);

    Optional<StoredUser> findById(String id);

    /**
     * Creates a new user. The password must already be hashed by the caller.
     *
     * @throws IllegalArgumentException if the username is already taken
     */
    StoredUser create(String username, String displayName, String passwordHash,
                      java.util.Map<String, String> attributes);

    /** Every known user, unsorted. */
    List<StoredUser> list();

    boolean delete(String id);
}