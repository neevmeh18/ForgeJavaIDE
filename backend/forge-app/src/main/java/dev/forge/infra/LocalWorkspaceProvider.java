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
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Workspaces backed by directories under {@code IDE_WORKSPACE_ROOT}.
 *
 * <p>Offers the mounted root itself as a workspace and each immediate subdirectory as another,
 * so "mount my project" and "mount my folder of projects" both work with no configuration.
 *
 * <p>Ids are derived by hashing the path relative to the root — stable across restarts, opaque
 * to clients, and never a path. Nothing outside this class can turn a {@code WorkspaceId} back
 * into a location, which is exactly the property that lets a container or cloud provider replace
 * this one without any other feature noticing.
 */
public final class LocalWorkspaceProvider implements WorkspaceProvider {

    private static final Log log = Log.of(LocalWorkspaceProvider.class);
    public static final String SCHEME = "local";

    private final Path root;
    private final Map<WorkspaceId, LocalFileSystem> fileSystems = new ConcurrentHashMap<>();

    public LocalWorkspaceProvider(Path workspaceRoot) {
        try {
            Files.createDirectories(workspaceRoot);
            this.root = workspaceRoot.toRealPath();
        } catch (IOException e) {
            throw ForgeException.unavailable("Workspace root is not usable: " + workspaceRoot);
        }
        log.with("root", root).info("Local workspace provider ready");
    }

    @Override
    public String scheme() {
        return SCHEME;
    }

    @Override
    public List<Workspace> discover() {
        List<Workspace> workspaces = new ArrayList<>();
        workspaces.add(describe(root));
        try (DirectoryStream<Path> children = Files.newDirectoryStream(root, Files::isDirectory)) {
            for (Path child : children) {
                if (!child.getFileName().toString().startsWith(".")) {
                    workspaces.add(describe(child));
                }
            }
        } catch (IOException e) {
            log.warn("Could not enumerate workspaces", e);
        }
        return workspaces;
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
        if (Files.exists(directory)) {
            throw ForgeException.conflict("A workspace directory named '" + folder + "' already exists");
        }
        try {
            Files.createDirectory(directory);
        } catch (IOException e) {
            throw ForgeException.normalize(e);
        }
        log.with("workspace", folder).info("Workspace directory created");
        return describe(directory);
    }

    @Override
    public Workspace open(WorkspaceId id) {
        Workspace workspace = find(id)
                .orElseThrow(() -> ForgeException.notFound("Unknown workspace: " + id));
        Path directory = root.resolve(workspace.location().path()).normalize();
        if (!Files.isDirectory(directory)) {
            throw ForgeException.notFound("Workspace directory is missing");
        }
        fileSystems.computeIfAbsent(id, key -> new LocalFileSystem(directory));
        return workspace;
    }

    @Override
    public void close(WorkspaceId id) {
        fileSystems.remove(id);
    }

    /**
     * The directory behind an open workspace. Only other <em>local</em> infrastructure may use
     * this — the Git provider and the workspace task reader need a real path. No feature does,
     * and none is given one.
     */
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

    private Workspace describe(Path directory) {
        String relative = root.equals(directory) ? "" : root.relativize(directory).toString();
        String name = relative.isEmpty() ? nameOfRoot() : directory.getFileName().toString();
        return new Workspace(identify(relative), name,
                new Workspace.Location(SCHEME, "", relative),
                Map.of("readOnly", String.valueOf(!Files.isWritable(directory))),
                Workspace.State.AVAILABLE, null);
    }

    private String nameOfRoot() {
        Path name = root.getFileName();
        return name == null ? "workspace" : name.toString();
    }

    /** Stable, opaque id: the location is hashed, never exposed. */
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
        String cleaned = name.strip().replaceAll("[^A-Za-z0-9._-]", "-");
        if (cleaned.isEmpty() || cleaned.startsWith(".") || cleaned.length() > 64) {
            throw ForgeException.invalidArgument("Invalid workspace name");
        }
        return cleaned;
    }
}
