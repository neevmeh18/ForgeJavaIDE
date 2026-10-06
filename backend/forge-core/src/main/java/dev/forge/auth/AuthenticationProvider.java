package dev.forge.auth;

import java.util.Map;
import java.util.Optional;

/**
 * Verifies credentials and produces a {@link User}.
 *
 * <p>The single-identity implementation checks a username and password from configuration. The
 * multiuser implementation authenticates against a {@link UserStore}. The interface exists because
 * replacing the provider is a concrete requirement — OAuth, OIDC, SSO and LDAP all fit behind this
 * shape — and because keeping it here stops authentication logic from leaking into features.
 */
public interface AuthenticationProvider {

    /** Stable id recorded on issued sessions, e.g. {@code password} or {@code multiuser}. */
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

    // ---- Optional interfaces ----------------------------------------------------------------

    /**
     * Optional: a provider that also supports creating new accounts.
     * Check with {@code provider instanceof Registrar} before casting.
     */
    interface Registrar {
        /**
         * Registers a new user. The password is a {@code char[]} so callers can wipe it after use.
         *
         * @return the created user
         * @throws dev.forge.core.ForgeException with code {@code CONFLICT} if username is taken
         */
        User register(String username, char[] password, String displayName, Map<String, String> extra);
    }
}
