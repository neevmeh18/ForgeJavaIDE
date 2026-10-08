package dev.forge.infra;

import dev.forge.auth.AuthenticationProvider;
import dev.forge.auth.User;
import dev.forge.core.ForgeException;
import dev.forge.core.Log;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Map;
import java.util.Optional;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * The single-identity password provider.
 *
 * <p>This is the "simple authentication initially" the framework asks for: one configured
 * account, no roles, no directory. It is still implemented properly — the configured password
 * is turned into a PBKDF2 hash at startup and the plaintext is wiped, verification is
 * constant-time, and a failure never says whether it was the username or the password that was
 * wrong.
 *
 * <p>Replacing it with OIDC, SSO or LDAP means writing another {@link AuthenticationProvider};
 * nothing else in the framework changes, because nothing else knows how identity is proved.
 */
public final class PasswordAuthenticationProvider implements AuthenticationProvider {

    private static final Log log = Log.of(PasswordAuthenticationProvider.class);
    private static final int ITERATIONS = 210_000;
    private static final int KEY_LENGTH_BITS = 256;

    private final java.util.concurrent.Semaphore checks = new java.util.concurrent.Semaphore(2);
    private long windowStart;
    private int attempts;

    private synchronized void admit() {
        long now = System.nanoTime();
        if (now - windowStart > 60_000_000_000L) { windowStart = now; attempts = 0; }
        if (++attempts > 30) throw ForgeException.unavailable("Login temporarily rate limited");
    }

    private final String username;
    private final byte[] salt = new byte[16];
    private final byte[] expectedHash;

    public PasswordAuthenticationProvider(String username, String password) {
        this.username = username;
        new SecureRandom().nextBytes(salt);
        char[] secret = password.toCharArray();
        try {
            this.expectedHash = derive(secret);
        } finally {
            java.util.Arrays.fill(secret, '\0');
        }
        log.info("Password authentication configured");
    }

    @Override
    public String id() {
        return "password";
    }

    @Override
    public Optional<User> authenticate(Credentials credentials) {
        // Always derive, even for an unknown username: skipping the work would let an attacker
        // distinguish "no such user" from "wrong password" by timing alone.
        admit();
        if (!checks.tryAcquire()) throw ForgeException.unavailable("Login busy; retry shortly");
        byte[] candidate;
        try { candidate = derive(credentials.secret()); } finally { checks.release(); }
        boolean matches = MessageDigest.isEqual(expectedHash, candidate)
                & username.equals(credentials.username());
        java.util.Arrays.fill(candidate, (byte) 0);
        if (!matches) {
            log.info("Authentication rejected");
            return Optional.empty();
        }
        return Optional.of(new User(dev.forge.core.Ids.UserId.of(username), username, id(),
                Map.of("provider", id())));
    }

    private byte[] derive(char[] secret) {
        try {
            PBEKeySpec spec = new PBEKeySpec(secret, salt, ITERATIONS, KEY_LENGTH_BITS);
            try {
                return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            } finally {
                spec.clearPassword();
            }
        } catch (java.security.NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw ForgeException.internal("Password hashing is unavailable", e);
        }
    }

    /** Never log or serialise the configured credential; this guards against accidents. */
    @Override
    public String toString() {
        return "PasswordAuthenticationProvider[username=" + username + "]";
    }
}
