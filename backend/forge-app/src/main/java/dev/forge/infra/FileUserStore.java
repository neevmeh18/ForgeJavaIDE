package dev.forge.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.forge.auth.UserStore;
import dev.forge.core.ForgeException;
import dev.forge.core.Log;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * File-backed user storage, one JSON document per user under {@code $IDE_DATA_DIR/state/users/}.
 *
 * <p>Users are cached in memory for fast lookups. Writes go through atomic temp-file replacement
 * so a partial write never corrupts a user record. Passwords are stored pre-hashed — this class
 * never sees a plaintext credential.
 */
public final class FileUserStore implements UserStore {

    private static final Log log = Log.of(FileUserStore.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path usersDir;
    private final Map<String, StoredUser> byUsername = new ConcurrentHashMap<>();
    private final Map<String, StoredUser> byId = new ConcurrentHashMap<>();

    public FileUserStore(Path dataDir) {
        this.usersDir = dataDir.resolve("state").resolve("users");
        try {
            Files.createDirectories(usersDir);
        } catch (IOException e) {
            throw ForgeException.unavailable("Users directory is not writable: " + usersDir);
        }
        loadExisting();
        log.with("count", byUsername.size()).info("User store ready");
    }

    @Override
    public Optional<StoredUser> findByUsername(String username) {
        return Optional.ofNullable(byUsername.get(normalize(username)));
    }

    @Override
    public Optional<StoredUser> findById(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public synchronized StoredUser create(String username, String displayName, String passwordHash,
                                          Map<String, String> attributes) {
        String key = normalize(username);
        if (byUsername.containsKey(key)) {
            throw new IllegalArgumentException("Username already exists: " + username);
        }
        String id = randomId();
        if (displayName == null || displayName.isBlank()) {
            displayName = username;
        }
        StoredUser user = new StoredUser(id, username, displayName, passwordHash,
                attributes == null ? Map.of() : Map.copyOf(attributes),
                System.currentTimeMillis());
        byUsername.put(key, user);
        byId.put(id, user);
        persist(user);
        log.with("username", username).with("userId", id).info("User created");
        return user;
    }

    @Override
    public List<StoredUser> list() {
        return List.copyOf(byUsername.values());
    }

    @Override
    public synchronized boolean delete(String id) {
        StoredUser user = byId.get(id);
        if (user == null) {
            return false;
        }
        byId.remove(id);
        byUsername.remove(normalize(user.username()));
        try {
            Files.deleteIfExists(userPath(user.username()));
        } catch (IOException e) {
            log.with("userId", id).warn("Could not delete user file", e);
        }
        log.with("userId", id).info("User deleted");
        return true;
    }

    // ---- internal ---------------------------------------------------------------------------

    private static String normalize(String username) {
        return username == null ? "" : username.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private Path userPath(String username) {
        String safe = username.replaceAll("[^A-Za-z0-9._-]", "_");
        return usersDir.resolve(safe + ".json");
    }

    @SuppressWarnings("unchecked")
    private void loadExisting() {
        if (!Files.isDirectory(usersDir)) {
            return;
        }
        try (Stream<Path> files = Files.list(usersDir)) {
            files.filter(p -> p.toString().endsWith(".json")).forEach(path -> {
                try {
                    Map<String, Object> raw = mapper.readValue(path.toFile(), Map.class);
                    if (raw == null || raw.isEmpty()) return;
                    StoredUser user = parseUser(raw);
                    if (user == null) {
                        log.warn("Skipping incomplete user file: " + path.getFileName());
                        return;
                    }
                    byUsername.put(normalize(user.username()), user);
                    byId.put(user.id(), user);
                } catch (IOException e) {
                    log.warn("Could not read user file: " + path.getFileName());
                }
            });
        } catch (IOException e) {
            log.warn("Could not list users directory");
        }
    }

    private StoredUser parseUser(Map<String, Object> raw) {
        String id = s(raw, "id");
        String username = s(raw, "username");
        String passwordHash = s(raw, "passwordHash");
        if (id == null || username == null || passwordHash == null) return null;
        String displayName = s(raw, "displayName");
        long createdAt = raw.get("createdAtEpochMillis") instanceof Number n
                ? n.longValue() : System.currentTimeMillis();
        Map<String, String> attrs = raw.get("attributes") instanceof Map<?, ?> m
                ? (Map<String, String>) m : Map.of();
        return new StoredUser(id, username,
                displayName == null ? username : displayName,
                passwordHash, attrs, createdAt);
    }

    private void persist(StoredUser user) {
        Path path = userPath(user.username());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", user.id());
        data.put("username", user.username());
        data.put("displayName", user.displayName());
        data.put("passwordHash", user.passwordHash());
        data.put("attributes", user.attributes());
        data.put("createdAtEpochMillis", user.createdAtEpochMillis());
        try {
            Files.createDirectories(path.getParent());
            Path tmp = Files.createTempFile(path.getParent(), ".user-", ".tmp");
            try {
                mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), data);
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                Files.deleteIfExists(tmp);
                throw e;
            }
        } catch (IOException e) {
            throw ForgeException.internal("Could not persist user: " + user.username(), e);
        }
    }

    private static String s(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof String s ? s : null;
    }

    private static String randomId() {
        byte[] bytes = new byte[8];
        new SecureRandom().nextBytes(bytes);
        return "usr-" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Override
    public String toString() {
        return "FileUserStore[count=" + byUsername.size() + "]";
    }
}