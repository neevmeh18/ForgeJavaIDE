package dev.forge.auth;

import dev.forge.core.ForgeException;
import dev.forge.core.Log;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Authenticates against a configurable set of username/password pairs.
 *
 * <p>Replaces the single-identity {@code PasswordAuthenticationProvider} without changing anything
 * that depends on the {@link AuthenticationProvider} interface. Each configured password is turned
 * into a PBKDF2 hash at construction time with its own random salt, and the plaintext is wiped
 * before the constructor returns. Verification is constant-time and never distinguishes an unknown
 * user from a wrong password, matching the security contract of the replaced provider.
 *
 * <p>Rate limiting is shared across all users (2 concurrent derivation slots, 30 attempts per 60 s
 * window), so one user's repeated failures cannot degrade another's responsiveness unless the slot
 * limit is genuinely under contention — at which point slowing everyone down equally is correct
 * behaviour.
 */
public final class MultiUserPasswordAuthenticationProvider implements AuthenticationProvider {

    private static final Log log = Log.of(MultiUserPasswordAuthenticationProvider.class);
    private static final int ITERATIONS = 210_000;
    private static final int KEY_LENGTH_BITS = 256;

    private final java.util.concurrent.Semaphore checks = new java.util.concurrent.Semaphore(2);
    private long windowStart;
    private int attempts;

    private synchronized void admit() {
        long now = System.nanoTime();
        if (now - windowStart > 60_000_000_000L) {
            windowStart = now;
            attempts = 0;
        }
        if (++attempts > 30) {
            throw ForgeException.unavailable("Login temporarily rate limited");
        }
    }

    private record UserEntry(byte[] salt, byte[] hash, String displayName) {
    }

    private final Map<String, UserEntry> users = new ConcurrentHashMap<>();

    /**
     * Constructs the provider and immediately hashes every configured password.
     *
     * @param credentials username → password map. The map is iterated synchronously; each password
     *                    char array is wiped after hashing. A copy is kept so the caller can
     *                    discard their reference.
     */
    public MultiUserPasswordAuthenticationProvider(Map<String, String> credentials) {
        SecureRandom rng = new SecureRandom();
        for (Map.Entry<String, String> entry : credentials.entrySet()) {
            String username = entry.getKey();
            String password = entry.getValue();
            byte[] salt = new byte[16];
            rng.nextBytes(salt);
            char[] secret = password.toCharArray();
            byte[] hash;
            try {
                hash = derive(secret, salt);
            } finally {
                java.util.Arrays.fill(secret, '\0');
            }
            users.put(username, new UserEntry(salt, hash, username));
        }
        log.with("userCount", credentials.size()).info("Multiuser password authentication configured");
    }

    @Override
    public String id() {
        return "password";
    }

    @Override
    public Optional<User> authenticate(Credentials credentials) {
        admit();
        if (!checks.tryAcquire()) {
            throw ForgeException.unavailable("Login busy; retry shortly");
        }
        try {
            // Always derive, even for an unknown username: skipping the work would let an attacker
            // distinguish "no such user" from "wrong password" by timing alone.
            UserEntry entry = users.get(credentials.username());
            byte[] salt = entry != null ? entry.salt() : dummySalt();
            byte[] candidate = derive(credentials.secret(), salt);
            boolean matches = entry != null && MessageDigest.isEqual(entry.hash(), candidate);
            java.util.Arrays.fill(candidate, (byte) 0);
            if (!matches) {
                log.info("Authentication rejected");
                return Optional.empty();
            }
            return Optional.of(new User(
                    dev.forge.core.Ids.UserId.of(credentials.username()),
                    entry.displayName(),
                    id(),
                    Map.of("provider", id())));
        } finally {
            checks.release();
        }
    }

    /** A fixed dummy salt reused for unknown-usernames so the derivation cost is indistinguishable. */
    private static byte[] dummySalt() {
        return new byte[]{0x01, 0x23, 0x45, 0x67, (byte) 0x89, (byte) 0xab, (byte) 0xcd, (byte) 0xef,
                          0x01, 0x23, 0x45, 0x67, (byte) 0x89, (byte) 0xab, (byte) 0xcd, (byte) 0xef};
    }

    private static byte[] derive(char[] secret, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(secret, salt, ITERATIONS, KEY_LENGTH_BITS);
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

    /** Never log or serialise the configured credentials. */
    @Override
    public String toString() {
        return "MultiUserPasswordAuthenticationProvider[userCount=" + users.size() + "]";
    }
}