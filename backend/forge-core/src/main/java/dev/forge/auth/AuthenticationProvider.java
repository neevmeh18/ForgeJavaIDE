package dev.forge.auth;

import java.util.Map;
import java.util.Optional;

/**
 * Verifies credentials and produces a {@link User}.
 *
 * <p>The only implementation today checks a username and password from configuration, which is
 * all a local or single-tenant deployment needs. The interface exists because replacing it is a
 * concrete requirement — OAuth, OIDC, SSO and LDAP all fit behind this shape — and because
 * keeping it here stops authentication logic from leaking into features.
 */
public interface AuthenticationProvider {

    /** Stable id recorded on issued sessions, e.g. {@code password} or {@code oidc}. */
    String id();

    /**
     * Credentials as supplied by a client. The secret is a {@code char[]} so callers can wipe it
     * after use instead of leaving a password interned in the string pool.
     */
    record Credentials(String username, char[] secret, Map<String, String> extra) {
        public Credentials {
            extra = extra == null ? Map.of() : Map.copyOf(extra);
        }

        /** Zeroes the secret. Call once authentication has finished, success or failure. */
        public void wipe() {
            if (secret != null) {
                java.util.Arrays.fill(secret, '\0');
            }
        }
    }

    /**
     * Returns the authenticated user, or empty when the credentials are wrong. Implementations
     * must not distinguish "unknown user" from "wrong password" to the caller.
     */
    Optional<User> authenticate(Credentials credentials);
}
