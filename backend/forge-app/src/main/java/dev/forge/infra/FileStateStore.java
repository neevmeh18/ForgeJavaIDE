package dev.forge.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.forge.core.ForgeException;
import dev.forge.core.Log;
import dev.forge.state.StateStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * State on disk, one JSON document per scope and owner, under {@code IDE_DATA_DIR}.
 *
 * <p>Enough for a single-node deployment and small enough to read: settings and persisted
 * workbench state are a few kilobytes, and a database would be infrastructure the first
 * milestone does not need. Swapping in one means implementing {@link StateStore} and changing a
 * line of the bootstrap.
 *
 * <p>{@code SESSION} state is held in memory only. It belongs to one live client and writing it
 * to disk would mean accumulating files nobody ever reads again.
 */
public final class FileStateStore implements StateStore {

    private static final Log log = Log.of(FileStateStore.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path root;
    private final Map<String, Map<String, Object>> sessionState = new ConcurrentHashMap<>();

    public FileStateStore(Path dataDir) {
        this.root = dataDir.resolve("state");
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw ForgeException.unavailable("State directory is not writable: " + root);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> read(Scope scope, String owner) {
        if (scope == Scope.SESSION) {
            return Map.copyOf(sessionState.getOrDefault(owner, Map.of()));
        }
        Path file = documentPath(scope, owner);
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        try {
            Map<String, Object> value = mapper.readValue(file.toFile(), Map.class);
            return value == null ? Map.of() : value;
        } catch (IOException e) {
            log.with("scope", scope).warn("Discarding unreadable state document", e);
            return Map.of();
        }
    }

    @Override
    public void write(Scope scope, String owner, Map<String, Object> values) {
        if (scope == Scope.SESSION) {
            sessionState.put(owner, new LinkedHashMap<>(values));
            return;
        }
        Path file = documentPath(scope, owner);
        try {
            Files.createDirectories(file.getParent());
            Path temporary = Files.createTempFile(file.getParent(), ".state-", ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), values);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw ForgeException.internal("Could not persist state", e);
        }
    }

    @Override
    public void clear(Scope scope, String owner) {
        if (scope == Scope.SESSION) {
            sessionState.remove(owner);
            return;
        }
        try {
            Files.deleteIfExists(documentPath(scope, owner));
        } catch (IOException e) {
            throw ForgeException.internal("Could not clear state", e);
        }
    }

    /**
     * Owners are user ids, workspace ids and session ids — all validated value types, but this
     * turns them into a filename anyway, so it re-checks rather than trusting the caller.
     */
    private Path documentPath(Scope scope, String owner) {
        String name = (owner == null || owner.isBlank() ? "default" : owner).replaceAll("[^A-Za-z0-9._-]", "_");
        if (name.contains("..")) {
            throw ForgeException.invalidArgument("Invalid state owner");
        }
        return root.resolve(scope.name().toLowerCase(java.util.Locale.ROOT)).resolve(name + ".json");
    }
}
