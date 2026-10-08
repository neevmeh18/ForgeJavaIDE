package dev.forge.infra;

import dev.forge.core.ForgeException;
import dev.forge.core.Lifecycle.Disposable;
import dev.forge.core.Log;
import dev.forge.filesystem.FileSystem;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Local, workspace-rooted filesystem with physical containment and resource bounds. */
public final class LocalFileSystem implements FileSystem {

    private static final Log log = Log.of(LocalFileSystem.class);

    private final Path root;
    private final long maxWorkspaceBytes;
    private final int maxDirectoryEntries;
    private final int maxTraversalEntries;
    private static final Object mutationLock = new Object();
    private final Path quotaRoot;

    public LocalFileSystem(Path root, long maxWorkspaceBytes, int maxDirectoryEntries, int maxTraversalEntries) {
        this(root, maxWorkspaceBytes, maxDirectoryEntries, maxTraversalEntries, root);
    }

    public LocalFileSystem(Path root, long maxWorkspaceBytes, int maxDirectoryEntries, int maxTraversalEntries, Path quotaRoot) {
        this.quotaRoot = quotaRoot.toAbsolutePath().normalize();
        try {
            SafePaths.noLinks(root);
            this.root = root.toRealPath();
        } catch (IOException e) {
            throw ForgeException.unavailable("Workspace root is not accessible: " + root);
        }
        this.maxWorkspaceBytes = maxWorkspaceBytes;
        this.maxDirectoryEntries = maxDirectoryEntries;
        this.maxTraversalEntries = maxTraversalEntries;
    }

    public Path root() {
        return root;
    }

    @Override
    public boolean isAvailable() {
        return Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) && Files.isReadable(root);
    }

    @Override
    public Stat stat(String path) {
        Path file = existing(path);
        try {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            return new Stat(path, attributes.isDirectory(), attributes.size(),
                    attributes.lastModifiedTime().toMillis(), !Files.isWritable(file));
        } catch (IOException e) {
            throw ForgeException.normalize(e);
        }
    }

    @Override
    public boolean exists(String path) {
        try {
            Path candidate = resolve(path);
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            verifyNoSymlinkComponents(candidate);
            return candidate.toRealPath().startsWith(root);
        } catch (IOException | ForgeException e) {
            return false;
        }
    }

    @Override
    public List<Entry> list(String path) {
        Path directory = existing(path);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw ForgeException.invalidArgument("Not a directory: " + path);
        }
        int visited = 0;
        List<Entry> entries = new ArrayList<>();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(directory)) {
            for (Path child : children) {
                if (++visited > maxDirectoryEntries || Thread.currentThread().isInterrupted())
                    throw ForgeException.unavailable("Directory listing budget exceeded");
                if (Files.isSymbolicLink(child)) {
                    continue;
                }
                if (entries.size() >= maxDirectoryEntries) {
                    throw ForgeException.unavailable("Directory contains too many entries to list safely");
                }
                if (!withinRootExisting(child)) {
                    continue;
                }
                boolean isDirectory = Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS);
                entries.add(new Entry(child.getFileName().toString(), relative(child), isDirectory,
                        isDirectory ? 0 : sizeOf(child), modifiedAt(child)));
            }
        } catch (IOException e) {
            throw ForgeException.normalize(e);
        }
        entries.sort(Comparator.comparing(Entry::directory).reversed()
                .thenComparing(entry -> entry.name().toLowerCase(java.util.Locale.ROOT)));
        return List.copyOf(entries);
    }

    @Override
    public byte[] read(String path, long maxBytes) {
        Path file = existing(path);
        try {
            long size = Files.size(file);
            if (size > maxBytes) {
                throw ForgeException.invalidArgument("File is larger than the readable limit");
            }
            try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                byte[] data = input.readNBytes((int) Math.min(Integer.MAX_VALUE, maxBytes + 1));
                if (data.length > maxBytes) throw ForgeException.invalidArgument("File exceeds read limit");
                return data;
            }
        } catch (IOException e) {
            throw ForgeException.normalize(e);
        }
    }

    @Override
    public void write(String path, byte[] content) {
        synchronized (mutationLock) {
            Path file = creatable(path);
            long previous = Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) ? sizeOf(file) : 0;
            ensureWorkspaceQuota(content.length - previous);
            try {
                Files.createDirectories(file.getParent());
                verifyNoSymlinkComponents(file.getParent());
                Path temporary = Files.createTempFile(file.getParent(), ".forge-", ".tmp");
                try {
                    Files.write(temporary, content);
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
                throw ForgeException.normalize(e);
            }
        }
    }

    @Override
    public void createDirectory(String path) {
        synchronized (mutationLock) {
            Path directory = creatable(path);
            ensureWorkspaceQuota(0);
            try {
                Files.createDirectories(directory);
                verifyNoSymlinkComponents(directory);
            } catch (IOException e) {
                throw ForgeException.normalize(e);
            }
        }
    }

    @Override
    public void createFile(String path) {
        synchronized (mutationLock) {
            Path file = creatable(path);
            ensureWorkspaceQuota(0);
            try {
                Files.createDirectories(file.getParent());
                verifyNoSymlinkComponents(file.getParent());
                Files.createFile(file);
            } catch (IOException e) {
                throw ForgeException.normalize(e);
            }
        }
    }

    @Override
    public void delete(String path, boolean recursive) {
        synchronized (mutationLock) {
            Path lexical = resolve(path);
            if (lexical.equals(root)) throw ForgeException.forbidden("Cannot delete workspace root");
            verifyParents(lexical);
            if (Files.isSymbolicLink(lexical)) {
                try {
                    Files.deleteIfExists(lexical);
                    return;
                } catch (IOException e) {
                    throw ForgeException.normalize(e);
                }
            }
            Path target = existing(path);
            try {
                if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                    if (!recursive) {
                        throw ForgeException.conflict("Directory is not empty; pass recursive");
                    }
                    assertTraversalBound(target);
                    AtomicInteger deletedEntries = new AtomicInteger();
                    Files.walkFileTree(target, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                            if (deletedEntries.incrementAndGet() > maxTraversalEntries || Thread.currentThread().isInterrupted())
                                throw ForgeException.unavailable("Delete budget exceeded");
                            verifyParents(file);
                            Files.deleteIfExists(file);
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                            if (exc != null) {
                                throw exc;
                            }
                            if (deletedEntries.incrementAndGet() > maxTraversalEntries || Thread.currentThread().isInterrupted())
                                throw ForgeException.unavailable("Delete budget exceeded");
                            verifyParents(dir);
                            Files.deleteIfExists(dir);
                            return FileVisitResult.CONTINUE;
                        }
                    });
                } else {
                    Files.deleteIfExists(target);
                }
            } catch (IOException e) {
                throw ForgeException.normalize(e);
            }
        }
    }

    @Override
    public void move(String from, String to) {
        synchronized (mutationLock) {
            Path source = existing(from);
            Path target = creatable(to);
            if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS) && target.startsWith(source)) {
                throw ForgeException.invalidArgument("A directory cannot be moved into itself");
            }
            try {
                Files.createDirectories(target.getParent());
                verifyNoSymlinkComponents(target.getParent());
                Files.move(source, target);
            } catch (IOException e) {
                throw ForgeException.normalize(e);
            }
        }
    }

    @Override
    public void copy(String from, String to) {
        synchronized (mutationLock) {
            Path source = existing(from);
            Path target = creatable(to);
            if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS) && target.startsWith(source)) {
                throw ForgeException.invalidArgument("A directory cannot be copied into itself");
            }
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw ForgeException.conflict("Copy destination already exists");
            TreeMeasure measure = measure(source);
            ensureWorkspaceQuota(measure.bytes());
            try {
                Files.createDirectories(target.getParent());
                verifyNoSymlinkComponents(target.getParent());
                if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
                    AtomicInteger visited = new AtomicInteger();
                    Files.walkFileTree(source, new SimpleFileVisitor<>() {
                        private void count() {
                            if (Thread.currentThread().isInterrupted() || visited.incrementAndGet() > maxTraversalEntries) {
                                throw ForgeException.unavailable("Filesystem operation exceeds traversal limit");
                            }
                        }

                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                            count();
                            Path destination = target.resolve(source.relativize(dir).toString()).normalize();
                            if (!destination.startsWith(root)) {
                                throw new IOException("Copy target escaped workspace");
                            }
                            Files.createDirectories(destination);
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                            count();
                            if (attrs.isSymbolicLink()) {
                                return FileVisitResult.CONTINUE;
                            }
                            Path destination = target.resolve(source.relativize(file).toString()).normalize();
                            SafePaths.noLinks(file);
                            SafePaths.noLinks(destination);
                            Files.copy(file, destination, LinkOption.NOFOLLOW_LINKS);
                            return FileVisitResult.CONTINUE;
                        }
                    });
                } else {
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                throw ForgeException.normalize(e);
            }
        }
    }

    @Override
    public Disposable watch(String path, boolean recursive, Consumer<Change> listener) {
        Path base = existing(path);
        WatchService service;
        try {
            service = base.getFileSystem().newWatchService();
        } catch (IOException e) {
            log.warn("Filesystem watching is unavailable", e);
            return () -> { };
        }
        Map<WatchKey, Path> keys = new HashMap<>();
        registerRecursively(service, base, keys, recursive);
        Thread worker = Thread.ofVirtual().name("forge-watch").start(() -> pump(service, keys, recursive, listener));
        return () -> {
            worker.interrupt();
            try {
                service.close();
            } catch (IOException ignored) {
                // Already gone.
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
                Path changed = directory.resolve(event.context().toString()).normalize();
                if (!changed.startsWith(root)) {
                    continue;
                }
                boolean deleted = event.kind() == StandardWatchEventKinds.ENTRY_DELETE;
                if (!deleted && (!withinRootExisting(changed) || Files.isSymbolicLink(changed))) {
                    continue;
                }
                boolean isDirectory = !deleted && Files.isDirectory(changed, LinkOption.NOFOLLOW_LINKS);
                if (recursive && isDirectory && event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                    registerRecursively(service, changed, keys, true);
                }
                // Delete events must not call toRealPath(): the file is already gone.
                listener.accept(new Change(kindOf(event.kind()), relative(changed), isDirectory));
            }
            if (!key.reset()) {
                keys.remove(key);
            }
        }
    }

    private void registerRecursively(WatchService service, Path base, Map<WatchKey, Path> keys, boolean recursive) {
        try (var walk = recursive ? Files.walk(base, 12) : Files.walk(base, 1)) {
            AtomicInteger count = new AtomicInteger();
            walk.limit(maxTraversalEntries + 1L).peek(path -> {
                        if (Thread.currentThread().isInterrupted()) throw ForgeException.cancelled("Watcher registration cancelled");
                    }).filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(this::withinRootExisting)
                    .takeWhile(path -> count.incrementAndGet() <= maxTraversalEntries)
                    .forEach(directory -> {
                        try {
                            if (keys.size() >= maxDirectoryEntries) return;
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

    private Path resolve(String path) {
        SafePaths.noLinks(root);
        Path candidate = root.resolve(path == null ? "" : path).normalize();
        if (!candidate.startsWith(root)) {
            throw ForgeException.forbidden("Path escapes the workspace");
        }
        return candidate;
    }

    private void verifyParents(Path path) {
        SafePaths.noLinks(path.getParent());
    }

    private Path existing(String path) {
        Path candidate = resolve(path);
        try {
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                throw ForgeException.notFound("No such file: " + path);
            }
            verifyNoSymlinkComponents(candidate);
            Path real = candidate.toRealPath();
            if (!real.startsWith(root)) {
                throw ForgeException.forbidden("Path escapes the workspace");
            }
            return real;
        } catch (IOException e) {
            throw ForgeException.normalize(e);
        }
    }

    private Path creatable(String path) {
        Path candidate = resolve(path);
        Path parent = candidate.getParent() == null ? root : candidate.getParent();
        try {
            Path ancestor = parent;
            while (ancestor != null && ancestor.startsWith(root)
                    && !Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
                ancestor = ancestor.getParent();
            }
            if (ancestor == null || !ancestor.startsWith(root)) {
                throw ForgeException.forbidden("Path escapes the workspace");
            }
            verifyNoSymlinkComponents(ancestor);
            if (!ancestor.toRealPath().startsWith(root)) {
                throw ForgeException.forbidden("Path escapes the workspace");
            }
            if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                verifyNoSymlinkComponents(candidate);
            }
            return candidate;
        } catch (IOException e) {
            throw ForgeException.normalize(e);
        }
    }

    private void verifyNoSymlinkComponents(Path candidate) throws IOException {
        SafePaths.noLinks(root);
        Path relative = root.relativize(candidate.normalize());
        Path current = root;
        for (Path segment : relative) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw ForgeException.forbidden("Symbolic links are not allowed in workspace paths");
            }
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                break;
            }
        }
    }

    private boolean withinRootExisting(Path candidate) {
        try {
            if (Files.isSymbolicLink(candidate)) {
                return false;
            }
            verifyNoSymlinkComponents(candidate);
            return candidate.toRealPath().startsWith(root);
        } catch (IOException | ForgeException e) {
            return false;
        }
    }

    private void ensureWorkspaceQuota(long deltaBytes) {
        SafePaths.noLinks(quotaRoot);
        TreeMeasure total = measure(quotaRoot);
        if (total.entries() >= maxTraversalEntries) throw ForgeException.unavailable("Workspace entry quota exceeded");
        long usage = total.bytes();
        if (usage > maxWorkspaceBytes - deltaBytes) {
            throw ForgeException.unavailable("Workspace storage quota exceeded");
        }
    }

    private void assertTraversalBound(Path base) {
        measure(base);
    }

    private TreeMeasure measure(Path base) {
        AtomicInteger entries = new AtomicInteger();
        AtomicLong bytes = new AtomicLong();
        try {
            Files.walkFileTree(base, new SimpleFileVisitor<>() {
                private void count() {
                    if (Thread.currentThread().isInterrupted() || entries.incrementAndGet() > maxTraversalEntries) {
                        throw ForgeException.unavailable("Filesystem operation exceeds traversal limit");
                    }
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    count();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    count();
                    if (!attrs.isSymbolicLink() && attrs.isRegularFile()) {
                        bytes.addAndGet(attrs.size());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            return new TreeMeasure(entries.get(), bytes.get());
        } catch (IOException e) {
            throw ForgeException.normalize(e);
        }
    }

    private record TreeMeasure(int entries, long bytes) { }

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
