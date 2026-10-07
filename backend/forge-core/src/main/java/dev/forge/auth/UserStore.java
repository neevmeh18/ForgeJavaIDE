package dev.forge.auth;

import java.util.List;
import java.util.Optional;

/**
 * Persistence contract for user accounts.
 *
 * <p>Implementations store user data however they choose — file system, database, in-memory —
 * as long as every operation is atomic from the caller's perspective and the store is safe
 * for concurrent access.
 */
public interface UserStore {

    /**
     * Looks up a user by login name. Returns empty if the username does not exist.
     */
    Optional<StoredUser> find(String username);

    /**
     * Returns every stored user account.
     */
    List<StoredUser> list();

    /**
     * Stores a new user. Throws {@link dev.forge.core.ForgeException#conflict} if the
     * username already exists.
     */
    void create(StoredUser user);

    /**
     * Replaces an existing user. Throws {@link dev.forge.core.ForgeException#notFound} if
     * the username does not exist.
     */
    void update(StoredUser user);

    /**
     * Removes a user account by username. Throws {@link dev.forge.core.ForgeException#notFound}
     * if the username does not exist. Idempotent if the implementation chooses not to throw.
     */
    void delete(String username);

    /**
     * Returns whether a user with the given username exists.
     */
    boolean exists(String username);

    /**
     * Returns the total number of stored user accounts.
     */
    int count();

    /**
     * Ensures any pending writes are persisted. A no-op for in-memory stores.
     */
    void flush();
}