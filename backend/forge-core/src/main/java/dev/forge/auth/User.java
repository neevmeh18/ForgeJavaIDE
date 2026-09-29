package dev.forge.auth;

import dev.forge.core.UserId;
import java.util.Map;











public record User(UserId id, String displayName, String providerId, Map<String, String> attributes) {

    public User {
        displayName = displayName == null || displayName.isBlank() ? id.value() : displayName;
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public static User of(String id, String displayName, String providerId) {
        return new User(UserId.of(id), displayName, providerId, Map.of());
    }
}
