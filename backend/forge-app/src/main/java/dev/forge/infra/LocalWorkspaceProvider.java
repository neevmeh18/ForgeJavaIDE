package dev.forge.infra;

import dev.forge.core.ForgeException;
import dev.forge.core.Ids.WorkspaceId;
import dev.forge.core.Log;
import dev.forge.filesystem.FileSystem;
import dev.forge.workspace.Workspace;
import dev.forge.workspace.WorkspaceProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Workspaces backed by real directories immediately under {@code IDE_WORKSPACE_ROOT}. */
public final class LocalWorkspaceProvider implements WorkspaceProvider {

    private static final Log log = Log.of(LocalWorkspaceProvider.class);
    private static final int MAX_DISCOVERED_WORKSPACES = 512;
    public static final String SCHEME = "local";

    private final Path root;
    private final long maxWorkspaceBytes;
    private final int maxDirectoryEntries;
    private final int maxTraversalEntries;
    private final Map<WorkspaceId, LocalFileSystem> fileSystems = new ConcurrentHashMap<>();

    public LocalWorkspaceProvider(Path workspaceRoot, long maxWorkspaceBytes,
                                  int maxDirectoryEntries, int maxTraversalEntries) {
        try {
            SafePaths.noLinks(workspaceRoot);
            Files.createDirectories(workspaceRoot);
            this.root = workspaceRoot.toRealPath();
        } catch (IOException e) {
            throw ForgeException.unavailable("Workspace root is not usable: " + workspaceRoot);
        }
        this.maxWorkspaceBytes = maxWorkspaceBytes;
        this.maxDirectoryEntries = maxDirectoryEntries;
        this.maxTraversalEntries = maxTraversalEntries;
        log.with("root", root).info("Local workspace provider ready");
    }

    @Override
    public String scheme() {
        return SCHEME;
    }

    @Override
    public List<Workspace> discover() {
        SafePaths.noLinks(root);
        int visited = 0;
        List<Workspace> workspaces = new ArrayList<>();
        workspaces.add(describe(root));
        try (DirectoryStream<Path> children = Files.newDirectoryStream(root)) {
            for (Path child : children) {
                if (++visited > maxTraversalEntries || Thread.currentThread().isInterrupted())
                    throw ForgeException.unavailable("Workspace discovery budget exceeded");
                if (workspaces.size() >= MAX_DISCOVERED_WORKSPACES) {
                    log.warn("Workspace discovery limit reached; additional directories are hidden");
                    break;
                }
                String name = child.getFileName().toString();
                if (name.startsWith(".") || Files.isSymbolicLink(child)
                        || !Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                Path real = safeRealChild(child);
                if (real != null) {
                    workspaces.add(describe(real));
                }
            }
        } catch (IOException e) {
            log.warn("Could not enumerate workspaces", e);
        }
        return List.copyOf(workspaces);
    }

    @Override
    public Optional<Workspace> find(WorkspaceId id) {
        return discover().stream().filter(workspace -> workspace.id().equals(id)).findFirst();
    }

    @Override
    public Workspace create(String name, Map<String, String> options) {
        String folder = sanitize(name);
        Path directory = root.resolve(folder).normalize();
        if (!directory.startsWith(root) || directory.equals(root)) {
            throw ForgeException.invalidArgument("Invalid workspace name");
        }
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw ForgeException.conflict("A workspace directory named '" + folder + "' already exists");
        }
        try {
            Files.createDirectory(directory);
            return describe(directory.toRealPath());
        } catch (IOException e) {
            throw ForgeException.normalize(e);
        }
    }

    @Override
    public Workspace open(WorkspaceId id) {
        Workspace workspace = find(id)
                .orElseThrow(() -> ForgeException.notFound("Unknown workspace: " + id));
        Path candidate = root.resolve(workspace.location().path()).normalize();
        if (!candidate.startsWith(root) || Files.isSymbolicLink(candidate)) {
            throw ForgeException.forbidden("Workspace path is not a real child of the configured root");
        }
        Path directory;
        try {
            directory = candidate.toRealPath();
        } catch (IOException e) {
            throw ForgeException.notFound("Workspace directory is missing");
        }
        if (!directory.startsWith(root) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw ForgeException.forbidden("Workspace path escapes the configured root");
        }
        fileSystems.computeIfAbsent(id, key -> new LocalFileSystem(
                directory, maxWorkspaceBytes, maxDirectoryEntries, maxTraversalEntries, root));
        return workspace;
    }

    @Override
    public void close(WorkspaceId id) {
        fileSystems.remove(id);
    }

    public Optional<Path> directory(WorkspaceId id) {
        LocalFileSystem fileSystem = fileSystems.get(id);
        return fileSystem == null ? Optional.empty() : Optional.of(fileSystem.root());
    }

    @Override
    public FileSystem fileSystem(WorkspaceId id) {
        FileSystem fileSystem = fileSystems.get(id);
        if (fileSystem == null) {
            throw ForgeException.unavailable("Workspace is not open: " + id);
        }
        return fileSystem;
    }

    private Path safeRealChild(Path child) {
        try {
            Path real = child.toRealPath();
            return real.startsWith(root) && real.getParent() != null && real.getParent().equals(root) ? real : null;
        } catch (IOException e) {
            return null;
        }
    }

    private Workspace describe(Path directory) {
        Path normalized;
        try {
            normalized = directory.toRealPath();
        } catch (IOException e) {
            throw ForgeException.normalize(e);
        }
        if (!normalized.startsWith(root)) {
            throw ForgeException.forbidden("Workspace path escapes the configured root");
        }
        String relative = root.equals(normalized) ? "" : root.relativize(normalized).toString();
        String name = relative.isEmpty() ? nameOfRoot() : normalized.getFileName().toString();
        return new Workspace(identify(relative), name,
                new Workspace.Location(SCHEME, "", relative.replace('\\', '/')),
                Map.of("readOnly", String.valueOf(!Files.isWritable(normalized))),
                Workspace.State.AVAILABLE, null);
    }

    private String nameOfRoot() {
        Path name = root.getFileName();
        return name == null ? "workspace" : name.toString();
    }

    private static WorkspaceId identify(String relativePath) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(relativePath.getBytes(StandardCharsets.UTF_8));
            String suffix = Base64.getUrlEncoder().withoutPadding().encodeToString(digest).substring(0, 16);
            return WorkspaceId.of("local-" + suffix);
        } catch (NoSuchAlgorithmException e) {
            throw ForgeException.internal("SHA-256 unavailable", e);
        }
    }

    private static String sanitize(String name) {
        String cleaned = name == null ? "" : name.strip().replaceAll("[^A-Za-z0-9._-]", "-");
        if (cleaned.isEmpty() || cleaned.startsWith(".") || cleaned.length() > 64) {
            throw ForgeException.invalidArgument("Invalid workspace name");
        }
        return cleaned;
    }
}
