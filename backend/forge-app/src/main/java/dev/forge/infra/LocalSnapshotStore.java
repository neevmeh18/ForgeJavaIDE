package dev.forge.infra;

import dev.forge.core.ForgeException;
import dev.forge.core.Ids;
import dev.forge.core.Ids.UserId;
import dev.forge.core.Ids.WorkspaceId;
import dev.forge.core.Log;
import dev.forge.snapshot.Snapshot;
import dev.forge.snapshot.SnapshotStore;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;

/** Stores workspace snapshots beneath the application data directory. */
public final class LocalSnapshotStore implements SnapshotStore {

    private static final Log log = Log.of(LocalSnapshotStore.class);
    private static final String MANIFEST = "snapshot.properties";
    private static final String CONTENTS = "contents";
    private static final long MAX_FILES = 25_000;
    private static final long MAX_BYTES = 512L * 1024 * 1024;

    private final Path root;
    private final LocalWorkspaceProvider workspaces;

    public LocalSnapshotStore(Path dataDirectory, LocalWorkspaceProvider workspaces) {
        this.workspaces = workspaces;
        try {
            Files.createDirectories(dataDirectory);
            Path resolvedData = dataDirectory.toRealPath();
            Path snapshots = resolvedData.resolve("snapshots");
            Files.createDirectories(snapshots);
            this.root = snapshots.toRealPath();
        } catch (IOException e) {
            throw ForgeException.unavailable("Snapshot storage is not usable");
        }
    }

    @Override
    public Snapshot create(UserId owner, WorkspaceId sourceWorkspace, String name) {
        Path source = workspaceDirectory(sourceWorkspace);
        Path ownerRoot = ownerDirectory(owner);
        String id = Ids.random("snapshot");
        Path temporary;
        try {
            temporary = Files.createTempDirectory(ownerRoot, ".snapshot-");
        } catch (IOException e) {
            throw ForgeException.normalize(e);
        }

        try {
            CopyStats stats = copyTree(source, temporary.resolve(CONTENTS), true);
            Snapshot snapshot = new Snapshot(id, owner, sourceWorkspace, name, Instant.now(),
                    stats.files(), stats.bytes());
            writeManifest(temporary.resolve(MANIFEST), snapshot);
            moveDirectory(temporary, ownerRoot.resolve(id));
            log.with("workspaceId", sourceWorkspace).with("snapshotId", id).info("Snapshot created");
            return snapshot;
        } catch (RuntimeException e) {
            deleteQuietly(temporary);
            throw e;
        }
    }

    @Override
    public List<Snapshot> list(UserId owner, WorkspaceId sourceWorkspace) {
        Path ownerRoot = ownerDirectory(owner);
        List<Snapshot> snapshots = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(ownerRoot, path ->
                Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path))) {
            for (Path entry : entries) {
                try {
                    Snapshot snapshot = readManifest(entry.resolve(MANIFEST));
                    if (snapshot.ownerId().equals(owner)
                            && snapshot.sourceWorkspaceId().equals(sourceWorkspace)) {
                        snapshots.add(snapshot);
                    }
                } catch (RuntimeException e) {
                    log.with("path", entry).warn("Ignoring unreadable snapshot");
                }
            }
        } catch (IOException e) {
            throw ForgeException.normalize(e);
        }
        snapshots.sort(Comparator.comparing(Snapshot::createdAt).reversed());
        return List.copyOf(snapshots);
    }

    @Override
    public Snapshot find(UserId owner, String snapshotId) {
        Path directory = snapshotDirectory(owner, snapshotId);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(directory)) {
            throw unknown(snapshotId);
        }
        Snapshot snapshot = readManifest(directory.resolve(MANIFEST));
        if (!snapshot.ownerId().equals(owner) || !snapshot.id().equals(snapshotId)) {
            throw unknown(snapshotId);
        }
        return snapshot;
    }

    @Override
    public Snapshot restore(UserId owner, String snapshotId, WorkspaceId targetWorkspace) {
        Snapshot snapshot = find(owner, snapshotId);
        Path contents = snapshotDirectory(owner, snapshotId).resolve(CONTENTS);
        Path target = workspaceDirectory(targetWorkspace);
        copyTree(contents, target, false);
        log.with("workspaceId", targetWorkspace).with("snapshotId", snapshotId).info("Snapshot restored");
        return snapshot;
    }

    @Override
    public void delete(UserId owner, String snapshotId) {
        find(owner, snapshotId);
        Path directory = snapshotDirectory(owner, snapshotId);
        try {
            deleteRecursively(directory);
        } catch (IOException e) {
            throw ForgeException.normalize(e);
        }
        log.with("snapshotId", snapshotId).info("Snapshot deleted");
    }

    private CopyStats copyTree(Path source, Path destination, boolean countLimits) {
        AtomicLong files = new AtomicLong();
        AtomicLong bytes = new AtomicLong();
        try (var walk = Files.walk(source)) {
            for (Path item : walk.toList()) {
                if (Files.isSymbolicLink(item)) {
                    continue;
                }
                Path relative = source.relativize(item);
                Path target = safeDestination(destination, relative);
                if (Files.isDirectory(item, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(target);
                    continue;
                }
                if (!Files.isRegularFile(item, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                long fileCount = files.incrementAndGet();
                long byteCount = bytes.addAndGet(Files.size(item));
                if (countLimits && (fileCount > MAX_FILES || byteCount > MAX_BYTES)) {
                    throw ForgeException.invalidArgument("Workspace is too large to snapshot");
                }
                copyFile(item, target);
            }
            return new CopyStats(files.get(), bytes.get());
        } catch (IOException e) {
            throw ForgeException.normalize(e);
        }
    }

    private static void copyFile(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".snapshot-", ".tmp");
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Path safeDestination(Path root, Path relative) throws IOException {
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root.normalize())) {
            throw ForgeException.forbidden("Snapshot entry escapes its destination");
        }
        Path cursor = root;
        for (Path part : root.relativize(target)) {
            cursor = cursor.resolve(part);
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(cursor)) {
                throw ForgeException.forbidden("Snapshot destination contains a symbolic link");
            }
        }
        return target;
    }

    private Path workspaceDirectory(WorkspaceId workspace) {
        return workspaces.directory(workspace)
                .orElseThrow(() -> ForgeException.unavailable("Workspace is not open: " + workspace));
    }

    private Path ownerDirectory(UserId owner) {
        Path directory = root.resolve(digest(owner.value()));
        try {
            Files.createDirectories(directory);
            Path real = directory.toRealPath();
            if (!real.startsWith(root)) {
                throw ForgeException.forbidden("Invalid snapshot owner");
            }
            return real;
        } catch (IOException e) {
            throw ForgeException.normalize(e);
        }
    }

    private Path snapshotDirectory(UserId owner, String snapshotId) {
        if (snapshotId == null || !snapshotId.matches("[A-Za-z0-9._:@-]{1,128}")) {
            throw unknown(snapshotId);
        }
        Path ownerRoot = ownerDirectory(owner);
        Path directory = ownerRoot.resolve(snapshotId).normalize();
        if (!directory.startsWith(ownerRoot)) {
            throw unknown(snapshotId);
        }
        return directory;
    }

    private static void writeManifest(Path path, Snapshot snapshot) {
        Properties values = new Properties();
        values.setProperty("id", snapshot.id());
        values.setProperty("ownerId", snapshot.ownerId().value());
        values.setProperty("sourceWorkspaceId", snapshot.sourceWorkspaceId().value());
        values.setProperty("name", snapshot.name());
        values.setProperty("createdAt", snapshot.createdAt().toString());
        values.setProperty("fileCount", String.valueOf(snapshot.fileCount()));
        values.setProperty("totalBytes", String.valueOf(snapshot.totalBytes()));
        try (OutputStream output = Files.newOutputStream(path)) {
            values.store(output, "Forge workspace snapshot");
        } catch (IOException e) {
            throw ForgeException.normalize(e);
        }
    }

    private static Snapshot readManifest(Path path) {
        Properties values = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            values.load(input);
            return new Snapshot(
                    required(values, "id"),
                    UserId.of(required(values, "ownerId")),
                    WorkspaceId.of(required(values, "sourceWorkspaceId")),
                    required(values, "name"),
                    Instant.parse(required(values, "createdAt")),
                    Long.parseLong(required(values, "fileCount")),
                    Long.parseLong(required(values, "totalBytes")));
        } catch (IOException | IllegalArgumentException e) {
            throw ForgeException.invalidArgument("Invalid snapshot metadata");
        }
    }

    private static String required(Properties values, String key) {
        String value = values.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing " + key);
        }
        return value;
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw ForgeException.internal("SHA-256 unavailable", e);
        }
    }

    private static void moveDirectory(Path source, Path target) {
        try {
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(source, target);
            }
        } catch (IOException e) {
            throw ForgeException.normalize(e);
        }
    }

    private static void deleteRecursively(Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var walk = Files.walk(directory)) {
            for (Path item : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(item);
            }
        }
    }

    private static void deleteQuietly(Path directory) {
        try {
            deleteRecursively(directory);
        } catch (IOException ignored) {
            // The original failure is more useful than cleanup failure.
        }
    }

    private static ForgeException unknown(String snapshotId) {
        return ForgeException.notFound("Unknown snapshot: " + snapshotId)
                .with("snapshotId", String.valueOf(snapshotId));
    }

    private record CopyStats(long files, long bytes) {
    }
}
