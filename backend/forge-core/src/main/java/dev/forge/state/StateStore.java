package dev.forge.state;

import java.util.Map;
import java.util.Optional;


















public interface StateStore {




    Map<String, Object> read(Scope scope, String owner);


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
