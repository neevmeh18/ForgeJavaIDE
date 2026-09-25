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
 * Password provider for the configured Forge application identities.
 *
 * <p>Each configured password is converted to a PBKDF2 hash at startup. Authentication failures
 * do not reveal whether the username or password was wrong.
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

    private record Account(String username, byte[] salt, byte[] expectedHash) {}

    private final java.util.List<Account> accounts;

    /** Backward-compatible single-account constructor used by existing tests and embedders. */
    public PasswordAuthenticationProvider(String username, String password) {
        this(Map.of(username, password));
    }

    public PasswordAuthenticationProvider(Map<String, String> configuredAccounts) {
        java.util.List<Account> built = new java.util.ArrayList<>();
        SecureRandom random = new SecureRandom();
        configuredAccounts.forEach((username, password) -> {
            byte[] salt = new byte[16];
            random.nextBytes(salt);
            char[] secret = password.toCharArray();
            try {
                built.add(new Account(username, salt, derive(secret, salt)));
            } finally {
                java.util.Arrays.fill(secret, '\0');
            }
        });
        this.accounts = java.util.List.copyOf(built);
        log.info("Password authentication configured for " + accounts.size() + " users");
    }

    @Override
    public String id() {
        return "password";
    }

    @Override
    public Optional<User> authenticate(Credentials credentials) {
        admit();
        if (!checks.tryAcquire()) throw ForgeException.unavailable("Login busy; retry shortly");
        try {
            Account matched = null;
            // Perform the expensive hash check for every configured account. This keeps unknown
            // usernames from taking a noticeably cheaper path than known usernames.
            for (Account account : accounts) {
                byte[] candidate = derive(credentials.secret(), account.salt());
                boolean passwordMatches = MessageDigest.isEqual(account.expectedHash(), candidate);
                java.util.Arrays.fill(candidate, (byte) 0);
                if (account.username().equals(credentials.username()) & passwordMatches) {
                    matched = account;
                }
            }
            if (matched == null) {
                log.info("Authentication rejected");
                return Optional.empty();
            }
            String username = matched.username();
            return Optional.of(new User(dev.forge.core.Ids.UserId.of(username), username, id(),
                    Map.of("provider", id())));
        } finally {
            checks.release();
        }
    }

    private byte[] derive(char[] secret, byte[] salt) {
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
        return "PasswordAuthenticationProvider[users=" + accounts.size() + "]";
    }
}
