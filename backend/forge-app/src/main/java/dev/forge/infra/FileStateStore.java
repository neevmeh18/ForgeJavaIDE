package dev.forge.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.forge.core.ForgeException;
import dev.forge.core.Log;
import dev.forge.state.StateStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Small bounded JSON state store used by the single-node runtime. */
public final class FileStateStore implements StateStore {

    private static final Log log = Log.of(FileStateStore.class);
    private static final int MAX_SESSION_OWNERS = 256;

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path root;
    private final long maxTotalBytes;
    private final long maxDocumentBytes;
    private final Map<String, Map<String, Object>> sessionState = new ConcurrentHashMap<>();
    private final Object lock = new Object();

    public FileStateStore(Path dataDir, long maxTotalBytes, long maxDocumentBytes) {
        try {
            SafePaths.noLinks(dataDir);
            Files.createDirectories(dataDir);
            Path dataRoot = dataDir.toRealPath();
            Path state = dataRoot.resolve("state");
            SafePaths.noLinks(state);
            Files.createDirectories(state);
            this.root = state.toRealPath();
        } catch (IOException e) {
            throw ForgeException.unavailable("State directory is not writable: " + dataDir);
        }
        this.maxTotalBytes = maxTotalBytes;
        this.maxDocumentBytes = Math.min(maxDocumentBytes, maxTotalBytes);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> read(Scope scope, String owner) {
        synchronized (lock) {
            if (scope == Scope.SESSION) {
                return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(sessionState.getOrDefault(ownerKey(owner), Map.of())));
            }
            Path file = documentPath(scope, owner);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                return Map.of();
            }
            rejectSymlink(file);
            try {
                if (Files.size(file) > maxDocumentBytes) {
                    log.with("scope", scope).warn("Ignoring oversized state document");
                    return Map.of();
                }
                Map<String, Object> value = mapper.readValue(file.toFile(), Map.class);
                return value == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(value));
            } catch (IOException e) {
                log.with("scope", scope).warn("Discarding unreadable state document", e);
                return Map.of();
            }
        }
    }

    @Override
    public void write(Scope scope, String owner, Map<String, Object> values) {
        synchronized (lock) {
            Map<String, Object> snapshot = values == null ? Map.of() : new LinkedHashMap<>(values);
            byte[] encoded = encode(snapshot);
            if (scope == Scope.SESSION) {
                String key = ownerKey(owner);
                if (!sessionState.containsKey(key) && sessionState.size() >= MAX_SESSION_OWNERS) {
                    throw ForgeException.unavailable("Too many session-state owners");
                }
                long total = encoded.length;
                for (var item : sessionState.entrySet()) if (!item.getKey().equals(key)) total += encode(item.getValue()).length;
                if (total > maxTotalBytes) throw ForgeException.unavailable("Session state quota exceeded");
                sessionState.put(key, snapshot);
                return;
            }
            Path file = documentPath(scope, owner);
            long existing = size(file);
            long projected = totalPersistentBytes() - existing + encoded.length;
            if (projected > maxTotalBytes) {
                throw ForgeException.unavailable("Persistent state quota exceeded");
            }
            try {
                Files.createDirectories(file.getParent());
                rejectSymlink(file.getParent());
                if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                    rejectSymlink(file);
                }
                Path temporary = Files.createTempFile(file.getParent(), ".state-", ".tmp");
                try {
                    Files.write(temporary, encoded);
                    try {
                        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING,
                                StandardCopyOption.ATOMIC_MOVE);
                    } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                    }
                } finally {
                    Files.deleteIfExists(temporary);
                }
            } catch (IOException e) {
                throw ForgeException.internal("Could not persist state", e);
            }
        }
    }

    @Override
    public void clear(Scope scope, String owner) {
        synchronized (lock) {
            if (scope == Scope.SESSION) {
                sessionState.remove(ownerKey(owner));
                return;
            }
            Path file = documentPath(scope, owner);
            try {
                if (Files.isSymbolicLink(file)) {
                    throw ForgeException.forbidden("State path contains a symbolic link");
                }
                Files.deleteIfExists(file);
            } catch (IOException e) {
                throw ForgeException.internal("Could not clear state", e);
            }
        }
    }

    @Override
    public void put(Scope scope, String owner, String key, Object value) {
        synchronized (lock) {
            Map<String, Object> current = new LinkedHashMap<>(read(scope, owner));
            if (value == null) {
                current.remove(key);
            } else {
                current.put(key, value);
            }
            write(scope, owner, current);
        }
    }

    private byte[] encode(Map<String, Object> values) {
        try {
            byte[] encoded = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(values);
            if (encoded.length > maxDocumentBytes) {
                throw ForgeException.invalidArgument("State document exceeds the configured size limit");
            }
            return encoded;
        } catch (IOException e) {
            throw ForgeException.invalidArgument("State value is not serializable");
        }
    }

    private long totalPersistentBytes() {
        try (var walk = Files.walk(root)) {
            long total = 0;
            int documents = 0;
            for (Path path : (Iterable<Path>) walk::iterator) {
                if (++documents > 10_000) {
                    throw ForgeException.unavailable("Persistent state contains too many documents");
                }
                if (Thread.currentThread().isInterrupted()) throw ForgeException.cancelled("State scan cancelled");
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
                total = Math.addExact(total, size(path));
                if (total > maxTotalBytes) return total;
            }
            return total;
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        } catch (IOException e) {
            throw ForgeException.internal("Could not measure persistent state", e);
        }
    }

    private long size(Path path) {
        try {
            return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ? Files.size(path) : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    private Path documentPath(Scope scope, String owner) {
        SafePaths.noLinks(root);
        String name = ownerKey(owner).replaceAll("[^A-Za-z0-9._-]", "_");
        if (name.contains("..")) {
            throw ForgeException.invalidArgument("Invalid state owner");
        }
        Path file = root.resolve(scope.name().toLowerCase(java.util.Locale.ROOT)).resolve(name + ".json").normalize();
        if (!file.startsWith(root)) {
            throw ForgeException.forbidden("State path escapes the data directory");
        }
        SafePaths.noLinks(file);
        return file;
    }

    private static String ownerKey(String owner) {
        return owner == null || owner.isBlank() ? "default" : owner;
    }

    private void rejectSymlink(Path path) {
        Path current = root;
        Path relative = root.relativize(path.normalize());
        for (Path segment : relative) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw ForgeException.forbidden("State path contains a symbolic link");
            }
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                break;
            }
        }
    }
}
