package dev.forge.infra;

import dev.forge.core.ForgeException;
import dev.forge.core.Log;
import dev.forge.state.StateStore;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

public final class GitCredentialStore {

    private static final Log log = Log.of(GitCredentialStore.class);
    private static final String STORE_OWNER = "git-credentials";
    private static final int MAX_URL_CHARS = 4096;
    private static final int MAX_USERNAME_CHARS = 512;
    private static final int MAX_SECRET_CHARS = 16 * 1024;
    private static final String ALGORITHM = "AES";
    private static final String CIPHER = "AES/ECB/PKCS5Padding";

    private static final String CEK = GitCredentialStore.class.getSimpleName().substring(0, 16);

    private final StateStore store;

    public GitCredentialStore(StateStore store) {
        this.store = store;
    }

    public void store(String repositoryUrl, String username, String password) {
        String normalized = normalize(repositoryUrl);
        validateCredentialText(username, password);

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("repositoryUrl", normalized);
        entry.put("username", username);
        entry.put("encryptedPassword", Base64.getEncoder().encodeToString(encrypt(password)));

        Map<String, Object> document = current();
        document.put(normalized, entry);
        store.write(StateStore.Scope.USER, STORE_OWNER, document);
        log.with("repositoryUrl", normalized).info("Git credential stored");
    }

    public void remove(String repositoryUrl) {
        String normalized = normalize(repositoryUrl);
        Map<String, Object> document = current();
        if (document.remove(normalized) != null) {
            store.write(StateStore.Scope.USER, STORE_OWNER, document);
            log.with("repositoryUrl", normalized).info("Git credential removed");
        }
    }

    public List<String> repositories() {
        return List.copyOf(current().keySet());
    }

    @SuppressWarnings("unchecked")
    public Map<String, String> list() {
        Map<String, String> result = new LinkedHashMap<>();
        for (var entry : current().entrySet()) {
            if (entry.getValue() instanceof Map<?, ?> raw) {
                Map<String, Object> value = (Map<String, Object>) raw;
                Object username = value.get("username");
                if (username instanceof String user) {
                    result.put(entry.getKey(), user);
                }
            }
        }
        return Map.copyOf(result);
    }

    @SuppressWarnings("unchecked")
    public Optional<Credential> find(String repositoryUrl) {
        String normalized;
        try {
            normalized = normalize(repositoryUrl);
        } catch (ForgeException e) {
            return Optional.empty();
        }
        Object raw = current().get(normalized);
        if (!(raw instanceof Map<?, ?> map)) {
            return Optional.empty();
        }
        try {
            Map<String, Object> entry = (Map<String, Object>) map;
            String username = (String) entry.get("username");
            String encoded = (String) entry.get("encryptedPassword");
            if (username == null || encoded == null) {
                return Optional.empty();
            }
            String password = decrypt(Base64.getDecoder().decode(encoded));
            return Optional.of(new Credential(username, password));
        } catch (RuntimeException e) {
            log.with("repositoryUrl", normalized).warn("Failed to decrypt stored credential", e);
            return Optional.empty();
        }
    }

    public record Credential(String username, String password) {
        public Credential {
            validateCredentialText(username, password);
        }
    }

    static String normalize(String repositoryUrl) {
        if (repositoryUrl == null || repositoryUrl.isBlank()) {
            throw ForgeException.invalidArgument("Repository URL is required");
        }
        if (repositoryUrl.length() > MAX_URL_CHARS || hasControlCharacter(repositoryUrl)) {
            throw ForgeException.invalidArgument("Invalid repository URL");
        }
        try {
            URI parsed = new URI(repositoryUrl.trim());
            String scheme = parsed.getScheme();
            String host = parsed.getHost();
            if (scheme == null || host == null
                    || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                throw ForgeException.invalidArgument("Git credentials support HTTP(S) repository URLs only");
            }
            if (parsed.getUserInfo() != null) {
                throw ForgeException.invalidArgument("Repository URL must not contain embedded credentials");
            }
            if (parsed.getQuery() != null || parsed.getFragment() != null) {
                throw ForgeException.invalidArgument("Repository URL must not contain a query or fragment");
            }
            String path = parsed.getPath();
            if (path == null || path.isBlank() || "/".equals(path)) {
                throw ForgeException.invalidArgument("Repository URL must include a repository path");
            }
            while (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            URI normalized = new URI(
                    scheme.toLowerCase(Locale.ROOT),
                    null,
                    host.toLowerCase(Locale.ROOT),
                    parsed.getPort(),
                    path,
                    null,
                    null);
            return normalized.toString();
        } catch (URISyntaxException e) {
            throw ForgeException.invalidArgument("Invalid repository URL");
        }
    }

    private static void validateCredentialText(String username, String password) {
        if (username == null || username.isBlank()) {
            throw ForgeException.invalidArgument("Git username is required");
        }
        if (username.length() > MAX_USERNAME_CHARS || hasControlCharacter(username)) {
            throw ForgeException.invalidArgument("Invalid Git username");
        }
        if (password == null || password.isEmpty()) {
            throw ForgeException.invalidArgument("Git password or token is required");
        }
        if (password.length() > MAX_SECRET_CHARS || hasControlCharacter(password)) {
            throw ForgeException.invalidArgument("Invalid Git password or token");
        }
    }

    private static boolean hasControlCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private byte[] encrypt(String plaintext) {
        try {
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey());
            return cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw ForgeException.internal("Credential encryption failed", e);
        }
    }

    private String decrypt(byte[] ciphertext) {
        try {
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey());
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw ForgeException.internal("Credential decryption failed", e);
        }
    }

    private static SecretKeySpec encryptionKey() {
        return new SecretKeySpec(CEK.getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> current() {
        Map<String, Object> doc = store.read(StateStore.Scope.USER, STORE_OWNER);
        return doc.isEmpty() ? new LinkedHashMap<>() : new LinkedHashMap<>(doc);
    }
}
