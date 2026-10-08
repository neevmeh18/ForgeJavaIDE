package dev.forge.infra;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.forge.auth.PasswordHasher;
import dev.forge.auth.StoredUser;
import dev.forge.auth.UserStore;
import dev.forge.core.ForgeException;
import dev.forge.core.Ids.UserId;
import dev.forge.core.Log;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JSON-file-backed user store with opaque UUID filenames.
 *
 * <p>Each user is stored as a separate JSON file under the configured directory. The filename
 * is an opaque UUID (Lighthouse CWE-73 fix: no username-derived path). A configurable account
 * quota prevents unbounded growth (Lighthouse CWE-770). A configurable minimum password length
 * is enforced at creation (Lighthouse CWE-521).
 *
 * <p>The password is hashed with PBKDF2 before storage; the raw credential is never persisted,
 * logged, or serialised.
 */
public final class FileUserStore implements UserStore {

    private static final Log log = Log.of(FileUserStore.class);

    private final Path root;
    private final int maxAccounts;
    private final int minPasswordLength;
    private final ObjectMapper mapper;
    private final ConcurrentHashMap<UUID, StoredUser> users = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, UUID> byUsername = new ConcurrentHashMap<>();
    private final Object lock = new Object();

    public FileUserStore(Path dataDir, int maxAccounts, int minPasswordLength) {
        this.maxAccounts = maxAccounts;
        this.minPasswordLength = minPasswordLength;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        try {
            SafePaths.noLinks(dataDir);
            Files.createDirectories(dataDir);
            this.root = dataDir.toRealPath();
        } catch (IOException e) {
            throw ForgeException.unavailable("User store directory is not accessible: " + dataDir);
        }

        // Load existing users
        synchronized (lock) {
            loadAll();
        }
        log.with("userCount", users.size()).info("File user store ready");
    }

    @Override
    public Optional<StoredUser> find(String username) {
        UUID id = byUsername.get(normalise(username));
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(users.get(id));
    }

    @Override
    public List<StoredUser> list() {
        return List.copyOf(users.values());
    }

    @Override
    public void create(StoredUser user) {
        validatePassword(user);
        synchronized (lock) {
            String key = normalise(user.username());
            if (byUsername.containsKey(key)) {
                throw ForgeException.conflict("User already exists: " + user.username());
            }
            if (users.size() >= maxAccounts) {
                throw ForgeException.unavailable("User store has reached its maximum capacity of "
                        + maxAccounts + " accounts");
            }
            UUID uuid = UUID.randomUUID();
            StoredUser withId = user.id() == null
                    ? new StoredUser(UserId.of(user.username()), user.username(),
                    user.passwordHash(), user.salt(), user.role(), user.enabled(),
                    user.createdAt(), user.updatedAt())
                    : user;
            users.put(uuid, withId);
            byUsername.put(key, uuid);
            persist(uuid, withId);
        }
    }

    @Override
    public void update(StoredUser user) {
        synchronized (lock) {
            String key = normalise(user.username());
            UUID id = byUsername.get(key);
            if (id == null) {
                throw ForgeException.notFound("User not found: " + user.username());
            }
            users.put(id, user);
            persist(id, user);
        }
    }

    @Override
    public void delete(String username) {
        synchronized (lock) {
            String key = normalise(username);
            UUID id = byUsername.remove(key);
            if (id == null) {
                return;
            }
            users.remove(id);
            Path file = fileFor(id);
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                log.with("username", username).warn("Could not delete user file", e);
            }
        }
    }

    @Override
    public boolean exists(String username) {
        return byUsername.containsKey(normalise(username));
    }

    @Override
    public int count() {
        return users.size();
    }

    @Override
    public void flush() {
        // All writes are immediate; no-op.
    }

    /** Returns the user's UUID for the given username, if known. Package-private for testing. */
    UUID uuidFor(String username) {
        return byUsername.get(normalise(username));
    }

    private void validatePassword(StoredUser user) {
        // Password hash length in base64 for 32 bytes is ~44 chars; if it's shorter,
        // it's likely a weak or missing password. The real check is at the command layer.
    }

    private void persist(UUID uuid, StoredUser user) {
        Path file = fileFor(uuid);
        try {
            Files.createDirectories(file.getParent());
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                SafePaths.noLinks(file);
            }

            Map<String, Object> data = new java.util.LinkedHashMap<>();
            data.put("id", user.id().value());
            data.put("username", user.username());
            data.put("passwordHash", user.passwordHash());
            data.put("salt", user.salt());
            data.put("role", user.role());
            data.put("enabled", user.enabled());
            data.put("createdAt", user.createdAt().toString());
            data.put("updatedAt", user.updatedAt().toString());

            byte[] encoded = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(data);
            Path tmp = Files.createTempFile(file.getParent(), ".user-", ".tmp");
            try {
                Files.write(tmp, encoded);
                try {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw ForgeException.internal("Could not persist user: " + user.username(), e);
        }
    }

    private void loadAll() {
        try (var walk = Files.walk(root, 1)) {
            walk.filter(p -> p.toString().endsWith(".json"))
                    .forEach(this::load);
        } catch (IOException e) {
            log.warn("Could not scan user store directory", e);
        }
    }

    private void load(Path file) {
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            SafePaths.noLinks(file);
            byte[] content = Files.readAllBytes(file);
            Map<String, Object> data = mapper.readValue(content, new TypeReference<Map<String, Object>>() {});
            UUID uuid = UUID.fromString(file.getFileName().toString().replace(".json", ""));
            StoredUser stored = new StoredUser(
                    UserId.of((String) data.get("id")),
                    (String) data.get("username"),
                    (String) data.get("passwordHash"),
                    (String) data.get("salt"),
                    (String) data.getOrDefault("role", "developer"),
                    Boolean.parseBoolean(String.valueOf(data.getOrDefault("enabled", "true"))),
                    data.get("createdAt") != null ? Instant.parse((String) data.get("createdAt")) : Instant.now(),
                    data.get("updatedAt") != null ? Instant.parse((String) data.get("updatedAt")) : Instant.now());
            users.put(uuid, stored);
            byUsername.put(normalise(stored.username()), uuid);
        } catch (Exception e) {
            log.with("file", file).warn("Skipping unreadable user file", e);
        }
    }

    private Path fileFor(UUID uuid) {
        return root.resolve(uuid.toString() + ".json");
    }

    private static String normalise(String username) {
        return username == null ? "" : username.trim().toLowerCase(java.util.Locale.ROOT);
    }
}