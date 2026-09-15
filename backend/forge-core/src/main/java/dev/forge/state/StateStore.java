package dev.forge.state;

import java.util.Map;
import java.util.Optional;

/**
 * Durable key/value documents, scoped.
 *
 * <p>Four scopes, kept apart because they have different lifetimes and different owners:
 * <ul>
 *   <li>{@code APPLICATION} — product-wide, e.g. which extensions are enabled.</li>
 *   <li>{@code USER} — follows one identity across workspaces: theme, keybinding overrides.</li>
 *   <li>{@code WORKSPACE} — belongs to the environment and is shared by every session in it.</li>
 *   <li>{@code SESSION} — one client's in-flight state; deliberately <em>not</em> persisted.</li>
 * </ul>
 *
 * <p>Nothing is written implicitly. Features decide what is worth keeping, which is why the
 * interface is a small explicit read/write rather than an ORM.
 *
 * <p>Values are JSON-shaped: string, number, boolean, list, map, null. Core never serialises
 * them; how a document reaches disk, a volume or a database is the implementation's business.
 */
public interface StateStore {

    enum Scope {
        APPLICATION,
        USER,
        WORKSPACE,
        SESSION
    }

    /** All values for one scope/owner pair. {@code owner} is empty for {@code APPLICATION}. */
    Map<String, Object> read(Scope scope, String owner);

    /** Replaces the document. Callers that want a merge read first. */
    void write(Scope scope, String owner, Map<String, Object> values);

    void clear(Scope scope, String owner);

    default Optional<Object> get(Scope scope, String owner, String key) {
        return Optional.ofNullable(read(scope, owner).get(key));
    }

    default void put(Scope scope, String owner, String key, Object value) {
        Map<String, Object> current = new java.util.LinkedHashMap<>(read(scope, owner));
        if (value == null) {
            current.remove(key);
        } else {
            current.put(key, value);
        }
        write(scope, owner, current);
    }
}
