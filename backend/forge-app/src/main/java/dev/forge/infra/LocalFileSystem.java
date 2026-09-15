package dev.forge.infra;

import dev.forge.core.ForgeException;
import dev.forge.core.Lifecycle.Disposable;
import dev.forge.core.Log;
import dev.forge.filesystem.FileSystem;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * A {@link FileSystem} over a directory on the machine running the backend.
 *
 * <p>The only implementation that ships, and the one Docker uses against the mounted
 * {@code /workspace}. Everything above it — editor, search, source control, language tooling —
 * is written against the interface, so a remote or object-store provider slots in without a
 * single change outside this package.
 *
 * <p><b>Containment.</b> Normalised paths are not enough on their own: a symlink inside the
 * workspace can still point at {@code /etc}. Every resolution therefore checks the <em>real</em>
 * path (or, for files being created, the real path of the parent) against the real root.
 */
public final class LocalFileSystem implements FileSystem {

    private static final Log log = Log.of(LocalFileSystem.class);

    private final Path root;

    public LocalFileSystem(Path root) {
        try {
            this.root = root.toRealPath();
        } catch (IOException e) {
            throw ForgeException.unavailable("Workspace root is not accessible: " + root);
        }
    }

    public Path root() {
        return root;
    }

    @Override
    public boolean isAvailable() {
        return Files.isDirectory(root) && Files.isReadable(root);
    }

    @Override
    public Stat stat(String path) {
        Path file = existing(path);
        try {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
            return new Stat(path, attributes.isDirectory(), attributes.size(),
                    attributes.lastModifiedTime().toMillis(), !Files.isWritable(file));
        } catch (IOException e) {
            throw ForgeException.normalize(e);
        }
    }

    @Override
    public boolean exists(String path) {
        try {
            return Files.exists(resolve(path));
        } catch (ForgeException e) {
            return false;
        }
    }

    @Override
    public List<Entry> list(String path) {
        Path directory = existing(path);
        if (!Files.isDirectory(directory)) {
            throw ForgeException.invalidArgument("Not a directory: " + path);
        }
        List<Entry> entries = new ArrayList<>();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(directory)) {
            for (Path child : children) {
                // A symlink pointing outside the workspace is simply not listed: showing it
                // would offer the user a path the rest of the API will refuse to open.
                if (!withinRoot(child)) {
                    continue;
                }
                boolean isDirectory = Files.isDirectory(child);
                long size = isDirectory ? 0 : sizeOf(child);
                entries.add(new Entry(child.getFileName().toString(), relative(child), isDirectory,
                        size, modifiedAt(child)));
            }
        } catch (IOException e) {
            throw ForgeException.normalize(e);
        }
        entries.sort(Comparator.comparing(Entry::directory).reversed()
                .thenComparing(entry -> entry.name().toLowerCase(java.util.Locale.ROOT)));
        return entries;
    }

    @Override
    public byte[] read(String path, long maxBytes) {
        Path file = existing(path);
        try {
            long size = Files.size(file);
            if (size > maxBytes) {
                throw ForgeException.invalidArgument("File is larger than the readable limit");
            }
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw ForgeException.normalize(e);
        }
    }

    @Override
    public void write(String path, byte[] content) {
        Path file = creatable(path);
        try {
            Files.createDirectories(file.getParent());
            // Write to a sibling and move: an interrupted save never leaves a half-written file.
            Path temporary = Files.createTempFile(file.getParent(), ".forge-", ".tmp");
            try {
                Files.write(temporary, content);
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException e) {
            throw ForgeException.normalize(e);
        }
    }

    @Override
    public void createDirectory(String path) {
        try {
            Files.createDirectories(creatable(path));
        } catch (IOException e) {
            throw ForgeException.normalize(e);
        }
    }

    @Override
    public void createFile(String path) {
        Path file = creatable(path);
        try {
            Files.createDirectories(file.getParent());
            Files.createFile(file);
        } catch (IOException e) {
            throw ForgeException.normalize(e);
        }
    }

    @Override
    public void delete(String path, boolean recursive) {
        Path target = existing(path);
        try {
            if (Files.isDirectory(target)) {
                if (!recursive) {
                    throw ForgeException.conflict("Directory is not empty; pass recursive");
                }
                try (var walk = Files.walk(target)) {
                    for (Path victim : walk.sorted(Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(victim);
                    }
                }
            } else {
                Files.deleteIfExists(target);
            }
        } catch (IOException e) {
            throw ForgeException.normalize(e);
        }
    }

    @Override
    public void move(String from, String to) {
        Path source = existing(from);
        Path target = creatable(to);
        try {
            Files.createDirectories(target.getParent());
            Files.move(source, target);
        } catch (IOException e) {
            throw ForgeException.normalize(e);
        }
    }

    @Override
    public void copy(String from, String to) {
        Path source = existing(from);
        Path target = creatable(to);
        try {
            Files.createDirectories(target.getParent());
            if (Files.isDirectory(source)) {
                try (var walk = Files.walk(source)) {
                    for (Path item : walk.toList()) {
                        Path destination = target.resolve(source.relativize(item).toString());
                        if (Files.isDirectory(item)) {
                            Files.createDirectories(destination);
                        } else {
                            Files.copy(item, destination, StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                }
            } else {
                Files.copy(source, target);
            }
        } catch (IOException e) {
            throw ForgeException.normalize(e);
        }
    }

    /**
     * Watches with the JDK's {@link WatchService} on a virtual thread. Best-effort by contract:
     * notifications may coalesce, and newly created directories are registered as they appear.
     */
    @Override
    public Disposable watch(String path, boolean recursive, Consumer<Change> listener) {
        Path base = existing(path);
        WatchService service;
        try {
            service = base.getFileSystem().newWatchService();
        } catch (IOException e) {
            log.warn("Filesystem watching is unavailable", e);
            return () -> {
            };
        }
        Map<WatchKey, Path> keys = new HashMap<>();
        registerRecursively(service, base, keys, recursive);

        Thread worker = Thread.ofVirtual().name("forge-watch").start(() -> pump(service, keys, recursive, listener));
        return () -> {
            worker.interrupt();
            try {
                service.close();
            } catch (IOException ignored) {
                // Closing a watch service that is already gone is not worth reporting.
            }
        };
    }

    private void pump(WatchService service, Map<WatchKey, Path> keys, boolean recursive,
                      Consumer<Change> listener) {
        while (!Thread.currentThread().isInterrupted()) {
            WatchKey key;
            try {
                key = service.poll(1, TimeUnit.SECONDS);
            } catch (InterruptedException | java.nio.file.ClosedWatchServiceException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (key == null) {
                continue;
            }
            Path directory = keys.get(key);
            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.OVERFLOW || directory == null) {
                    continue;
                }
                Path changed = directory.resolve(event.context().toString());
                if (!withinRoot(changed)) {
                    continue;
                }
                boolean isDirectory = Files.isDirectory(changed);
                if (recursive && isDirectory && event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                    registerRecursively(service, changed, keys, true);
                }
                listener.accept(new Change(kindOf(event.kind()), relative(changed), isDirectory));
            }
            if (!key.reset()) {
                keys.remove(key);
            }
        }
    }

    private void registerRecursively(WatchService service, Path base, Map<WatchKey, Path> keys, boolean recursive) {
        try (var walk = recursive ? Files.walk(base, 12) : Files.walk(base, 1)) {
            walk.filter(Files::isDirectory).filter(this::withinRoot).forEach(directory -> {
                try {
                    keys.put(directory.register(service,
                            StandardWatchEventKinds.ENTRY_CREATE,
                            StandardWatchEventKinds.ENTRY_MODIFY,
                            StandardWatchEventKinds.ENTRY_DELETE), directory);
                } catch (IOException e) {
                    log.with("path", directory).debug("Could not watch directory");
                }
            });
        } catch (IOException | UncheckedIOException e) {
            log.with("path", base).debug("Could not enumerate directories to watch");
        }
    }

    private static ChangeKind kindOf(WatchEvent.Kind<?> kind) {
        if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
            return ChangeKind.CREATED;
        }
        return kind == StandardWatchEventKinds.ENTRY_DELETE ? ChangeKind.DELETED : ChangeKind.CHANGED;
    }

    /** Resolves without requiring existence, rejecting anything that leaves the root. */
    private Path resolve(String path) {
        Path candidate = root.resolve(path).normalize();
        if (!candidate.startsWith(root)) {
            throw ForgeException.forbidden("Path escapes the workspace");
        }
        return candidate;
    }

    /** For reads and mutations of something that must already be there. Follows symlinks. */
    private Path existing(String path) {
        Path candidate = resolve(path);
        try {
            Path real = candidate.toRealPath();
            if (!real.startsWith(root)) {
                log.with("path", path).warn("Rejected path that resolves outside the workspace");
                throw ForgeException.forbidden("Path escapes the workspace");
            }
            return real;
        } catch (java.nio.file.NoSuchFileException e) {
            throw ForgeException.notFound("No such file: " + path);
        } catch (IOException e) {
            throw ForgeException.normalize(e);
        }
    }

    /** For creation: the file may not exist yet, so the parent is what gets verified. */
    private Path creatable(String path) {
        Path candidate = resolve(path);
        Path parent = candidate.getParent() == null ? root : candidate.getParent();
        Path existingAncestor = parent;
        while (!Files.exists(existingAncestor) && existingAncestor.startsWith(root)) {
            existingAncestor = existingAncestor.getParent();
        }
        try {
            if (!existingAncestor.toRealPath().startsWith(root)) {
                throw ForgeException.forbidden("Path escapes the workspace");
            }
        } catch (IOException e) {
            throw ForgeException.normalize(e);
        }
        return candidate;
    }

    private boolean withinRoot(Path candidate) {
        try {
            return candidate.toRealPath().startsWith(root);
        } catch (IOException e) {
            // A broken symlink or a file removed mid-listing: not resolvable, so not offered.
            return false;
        }
    }

    private String relative(Path candidate) {
        Path normalized = candidate.normalize();
        return normalized.equals(root) ? "" : root.relativize(normalized).toString().replace('\\', '/');
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0;
        }
    }

    private static long modifiedAt(Path file) {
        try {
            return Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }
}
