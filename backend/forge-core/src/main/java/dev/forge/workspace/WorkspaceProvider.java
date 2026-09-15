package dev.forge.workspace;

import dev.forge.core.Ids.WorkspaceId;
import dev.forge.filesystem.FileSystem;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Supplies workspaces of one {@link #scheme()} and the filesystem behind each.
 *
 * <p>This is the seam that keeps local, Docker and cloud deployments on one architecture. The
 * local provider hands back a directory; a future container or cloud provider hands back a
 * remote volume. Nothing above this interface changes, because nothing above it ever learns
 * which provider answered.
 */
public interface WorkspaceProvider {

    /** The {@link Workspace.Location#scheme()} this provider serves, e.g. {@code local}. */
    String scheme();

    /** Workspaces this provider can offer without opening them. */
    List<Workspace> discover();

    Optional<Workspace> find(WorkspaceId id);

    /** Creates a new workspace. Options are provider-specific and validated by the provider. */
    Workspace create(String name, Map<String, String> options);

    /**
     * Prepares a workspace for use — mount a volume, start a remote container, verify a path.
     * Returns the workspace in a state the caller can rely on.
     */
    Workspace open(WorkspaceId id);

    void close(WorkspaceId id);

    /** The filesystem rooted at this workspace. Called after {@link #open}. */
    FileSystem fileSystem(WorkspaceId id);
}
