package dev.forge.auth;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Arrays;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * PBKDF2-based password hashing utility.
 *
 * <p>All methods are static and thread-safe. Passwords are accepted as {@code char[]} and
 * zeroed after use to avoid leaving them in the string pool.
 *
 * <p>Configuration:
 * <ul>
 *   <li>Algorithm: PBKDF2WithHmacSHA256</li>
 *   <li>Iterations: 210 000 (OWASP 2023 minimum for PBKDF2-HMAC-SHA256)</li>
 *   <li>Salt: 16 bytes from {@link SecureRandom}</li>
 *   <li>Derived key length: 256 bits</li>
 * </ul>
 */
public final class PasswordHasher {

    private static final int ITERATIONS = 210_000;
    private static final int SALT_LENGTH = 16;
    private static final int KEY_LENGTH_BITS = 256;
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {
    }

    /**
     * Derives a 256-bit key from the password and salt using PBKDF2-HMAC-SHA256.
     */
    public static byte[] hash(char[] password, byte[] salt) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, KEY_LENGTH_BITS);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new RuntimeException("PBKDF2 is unavailable on this JVM", e);
        } finally {
            spec.clearPassword();
        }
    }

    /**
     * Verifies a password against a stored hash and salt. Uses constant-time comparison
     * to prevent timing side-channels.
     */
    public static boolean verify(char[] password, byte[] salt, byte[] expectedHash) {
        byte[] candidate = hash(password, salt);
        try {
            return MessageDigest.isEqual(expectedHash, candidate);
        } finally {
            Arrays.fill(candidate, (byte) 0);
        }
    }

    /**
     * Generates a new random salt.
     */
    public static byte[] generateSalt() {
        byte[] salt = new byte[SALT_LENGTH];
        RANDOM.nextBytes(salt);
        return salt;
    }
}