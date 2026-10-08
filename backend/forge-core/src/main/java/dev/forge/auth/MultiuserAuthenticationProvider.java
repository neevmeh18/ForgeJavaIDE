package dev.forge.auth;

import dev.forge.core.ForgeException;
import dev.forge.core.Ids.UserId;
import dev.forge.core.Log;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An {@link AuthenticationProvider} that authenticates against a {@link UserStore}.
 *
 * <p>Every user has a separate account with a PBKDF2-hashed password. Unknown usernames get
 * dummy PBKDF2 work so that "no such user" and "wrong password" are indistinguishable by
 * timing. Rate limiting is applied per username: after 10 failed attempts within 60 seconds
 * the account is temporarily blocked.
 *
 * <p>The role is passed through the {@link User#attributes()} map under the key {@code "role"}.
 */
public final class MultiuserAuthenticationProvider implements AuthenticationProvider {

    private static final Log log = Log.of(MultiuserAuthenticationProvider.class);
    private static final int MAX_ATTEMPTS = 10;
    private static final long RATE_WINDOW_NANOS = 60_000_000_000L;
    private static final byte[] DUMMY_SALT = new byte[16];

    private final UserStore userStore;
    private final ConcurrentHashMap<String, RateLimit> rateLimits = new ConcurrentHashMap<>();

    public MultiuserAuthenticationProvider(UserStore userStore) {
        this.userStore = userStore;
        log.info("Multiuser authentication configured");
    }

    @Override
    public String id() {
        return "multiuser-password";
    }

    @Override
    public Optional<User> authenticate(Credentials credentials) {
        String username = sanitize(credentials.username());
        if (username.isEmpty()) {
            return Optional.empty();
        }

        // Rate limit check — applied before any work to avoid wasting CPU on blocked users
        RateLimit limit = rateLimits.compute(username, (key, existing) -> {
            long now = System.nanoTime();
            if (existing == null || now - existing.windowStart > RATE_WINDOW_NANOS) {
                return new RateLimit(now, 1);
            }
            return new RateLimit(existing.windowStart, existing.count + 1);
        });

        if (limit.count > MAX_ATTEMPTS) {
            throw ForgeException.unauthorized("Invalid username or password");
        }

        // Look up the user; if not found, do dummy work to prevent user enumeration
        Optional<StoredUser> stored = userStore.find(username);
        byte[] salt;
        byte[] expectedHash;
        if (stored.isPresent()) {
            StoredUser user = stored.get();
            if (!user.enabled()) {
                // Perform dummy work anyway to mask the difference
                doDummyWork();
                return Optional.empty();
            }
            salt = Base64.getDecoder().decode(user.salt());
            expectedHash = Base64.getDecoder().decode(user.passwordHash());
        } else {
            doDummyWork();
            return Optional.empty();
        }

        char[] secret = credentials.secret();
        try {
            boolean matches = PasswordHasher.verify(secret, salt, expectedHash);
            if (!matches) {
                return Optional.empty();
            }
            // Successful login resets the rate-limit counter
            rateLimits.remove(username);
            Map<String, String> attributes = new java.util.LinkedHashMap<>();
            attributes.put("role", stored.get().role());
            attributes.put("provider", id());
            return Optional.of(new User(
                    UserId.of(username), username, id(), Map.copyOf(attributes)));
        } finally {
            // Ensure the secret is wiped even if verify throws
            if (secret != null) {
                Arrays.fill(secret, '\0');
            }
        }
    }

    /**
     * Performs dummy PBKDF2 work indistinguishable from real verification, so that
     * "unknown user" cannot be distinguished from "wrong password" by timing alone.
     */
    private static void doDummyWork() {
        char[] dummy = new char[]{'d', 'u', 'm', 'm', 'y'};
        try {
            PasswordHasher.verify(dummy, DUMMY_SALT, DUMMY_SALT);
        } finally {
            Arrays.fill(dummy, '\0');
        }
    }

    private static String sanitize(String username) {
        if (username == null) {
            return "";
        }
        String trimmed = username.trim();
        if (trimmed.length() > 128) {
            trimmed = trimmed.substring(0, 128);
        }
        // Reject usernames with control characters
        if (trimmed.chars().anyMatch(c -> c < 0x20 || c == 0x7F)) {
            return "";
        }
        return trimmed;
    }

    private record RateLimit(long windowStart, int count) {
    }
}