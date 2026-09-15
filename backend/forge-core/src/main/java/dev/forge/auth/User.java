package dev.forge.auth;

import dev.forge.core.Ids.UserId;
import java.util.Map;

/**
 * An authenticated identity.
 *
 * <p>One flat identity model, on purpose: no roles, no organisations, no teams. Those are real
 * requirements later, and the place they will be added is here plus {@link Authorizer} — not
 * scattered through features, which is why no feature is allowed to inspect a user directly.
 *
 * <p>{@code attributes} carries provider-supplied claims (display name, email, an OIDC subject)
 * without the framework committing to a schema for them. Secrets never belong in it.
 */
public record User(UserId id, String displayName, String providerId, Map<String, String> attributes) {

    public User {
        displayName = displayName == null || displayName.isBlank() ? id.value() : displayName;
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public static User of(String id, String displayName, String providerId) {
        return new User(UserId.of(id), displayName, providerId, Map.of());
    }
}
