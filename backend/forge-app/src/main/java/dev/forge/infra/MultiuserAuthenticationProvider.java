package dev.forge.infra;

import dev.forge.auth.AuthenticationProvider;
import dev.forge.auth.User;
import dev.forge.auth.UserStore;
import dev.forge.core.ForgeException;
import dev.forge.core.Log;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * An {@link AuthenticationProvider} backed by a {@link UserStore}.
 *
 * <p>Replaces {@link PasswordAuthenticationProvider} when multiuser mode is active. Every user
 * gets their own PBKDF2-derived password hash, stored in the user store. Implements
 * {@link Registrar} so callers can register new accounts.
 *
 * <p>Security properties:
 * <ul>
 *   <li>PBKDF2-HMAC-SHA256 with 210 000 iterations
 *   <li>Per-user random 16-byte salt persisted alongside the hash
 *   <li>Constant-time comparison via {@link MessageDigest#isEqual}
 *   <li>Plaintext wiped immediately after each hashing operation
 * </ul>
 */
public final class MultiuserAuthenticationProvider
        implements AuthenticationProvider, AuthenticationProvider.Registrar {

    private static final Log log = Log.of(MultiuserAuthenticationProvider.class);
    private static final int ITERATIONS = 210_000;
    private static final int KEY_LENGTH_BITS = 256;

    private final UserStore store;

    public MultiuserAuthenticationProvider(UserStore store) {
        this.store = store;
        log.with("userCount", store.list().size()).info("Multiuser authentication ready");
    }

    @Override
    public String id() {
        return "multiuser";
    }

    @Override
    public Optional<User> authenticate(Credentials credentials) {
        String username = credentials.username();
        Optional<UserStore.StoredUser> found = store.findByUsername(username);
        if (found.isEmpty()) {
            // Derive a dummy hash so timing is indistinguishable from an existing user.
            byte[] dummy = derive(credentials.secret(), new byte[16], ITERATIONS);
            java.util.Arrays.fill(dummy, (byte) 0);
            log.with("username", username).info("Authentication rejected — unknown user");
            return Optional.empty();
        }
        UserStore.StoredUser stored = found.get();
        byte[] candidate = derive(credentials.secret(), decodeSalt(stored.passwordHash()),
                decodeIterations(stored.passwordHash()));
        boolean matches = MessageDigest.isEqual(candidate, decodeHash(stored.passwordHash()));
        java.util.Arrays.fill(candidate, (byte) 0);
        if (!matches) {
            log.with("username", username).info("Authentication rejected — wrong password");
            return Optional.empty();
        }
        return Optional.of(new User(dev.forge.core.Ids.UserId.of(stored.id()),
                stored.displayName(), id(),
                Map.of("provider", id(), "username", stored.username())));
    }

    @Override
    public User register(String username, char[] password, String displayName,
                         Map<String, String> extra) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        byte[] hash = derive(password, salt, ITERATIONS);
        String encoded = encodeHash(salt, hash);
        java.util.Arrays.fill(hash, (byte) 0);

        UserStore.StoredUser created;
        try {
            created = store.create(username,
                    displayName == null || displayName.isBlank() ? username : displayName,
                    encoded, extra == null ? Map.of() : Map.copyOf(extra));
        } catch (IllegalArgumentException e) {
            throw ForgeException.conflict("Username is already taken: " + username)
                    .with("username", username);
        }
        log.with("username", username).with("userId", created.id()).info("User registered");
        return new User(dev.forge.core.Ids.UserId.of(created.id()),
                created.displayName(), id(),
                Map.of("provider", id(), "username", created.username()));
    }

    // ---- password hashing ------------------------------------------------------------------

    /** Format: {@code $pbkdf2-sha256$<iterations>$<base64-salt>$<base64-hash>}. */
    static String encodeHash(byte[] salt, byte[] hash) {
        String saltB64 = Base64.getEncoder().encodeToString(salt);
        String hashB64 = Base64.getEncoder().encodeToString(hash);
        return "$pbkdf2-sha256$" + ITERATIONS + "$" + saltB64 + "$" + hashB64;
    }

    static byte[] decodeSalt(String encoded) {
        String[] parts = encoded.split("\\$");
        if (parts.length < 5) return new byte[16];
        return Base64.getDecoder().decode(parts[parts.length - 2]);
    }

    static byte[] decodeHash(String encoded) {
        String[] parts = encoded.split("\\$");
        if (parts.length < 5) return new byte[32];
        return Base64.getDecoder().decode(parts[parts.length - 1]);
    }

    static int decodeIterations(String encoded) {
        try {
            String[] parts = encoded.split("\\$");
            return parts.length >= 4 ? Integer.parseInt(parts[2]) : ITERATIONS;
        } catch (NumberFormatException e) {
            return ITERATIONS;
        }
    }

    private static byte[] derive(char[] secret, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(secret, salt, iterations, KEY_LENGTH_BITS);
            try {
                return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                        .generateSecret(spec).getEncoded();
            } finally {
                spec.clearPassword();
            }
        } catch (java.security.NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw ForgeException.internal("Password hashing is unavailable", e);
        }
    }

    @Override
    public String toString() {
        return "MultiuserAuthenticationProvider[userCount=" + store.list().size() + "]";
    }
}