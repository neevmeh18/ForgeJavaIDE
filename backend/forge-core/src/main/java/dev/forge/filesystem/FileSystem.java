package dev.forge.filesystem;

import dev.forge.core.Ids.WorkspaceId;
import dev.forge.core.Lifecycle.Disposable;
import java.util.List;
import java.util.function.Consumer;

/**
 * The filesystem capability: one workspace's storage, whatever is actually behind it.
 *
 * <p>This is the single abstraction the editor, search, source control and language tooling
 * share. Implementations may be a local directory, a bind-mounted volume in a container, a
 * remote host or an in-memory tree; nothing above this interface is allowed to care, which is
 * what makes the same IDE code run locally and in the cloud.
 *
 * <p>Instances are <em>rooted</em> at their workspace: every {@code path} argument is
 * workspace-relative and already normalised by {@link Resource}. An implementation must still
 * verify that the resolved physical location stays inside its root — symlinks are the obvious
 * way a normalised path can still escape.
 */
public interface FileSystem {

    /** Resolves the filesystem serving a workspace. Supplied by the workspace feature. */
    @FunctionalInterface
    interface Locator {
        FileSystem forWorkspace(WorkspaceId workspace);
    }

    record Stat(String path, boolean directory, long size, long modifiedAt, boolean readOnly) {
    }

    record Entry(String name, String path, boolean directory, long size, long modifiedAt) {
    }

    enum ChangeKind {
        CREATED,
        CHANGED,
        DELETED
    }

    record Change(ChangeKind kind, String path, boolean directory) {
    }

    Stat stat(String path);

    boolean exists(String path);

    /** Directory children, directories first then names, case-insensitively. */
    List<Entry> list(String path);

    /** Reads at most {@code maxBytes}; larger content fails rather than exhausting memory. */
    byte[] read(String path, long maxBytes);

    void write(String path, byte[] content);

    void createDirectory(String path);

    void createFile(String path);

    void delete(String path, boolean recursive);

    void move(String from, String to);

    void copy(String from, String to);

    /**
     * Observes changes under {@code path}. Change notification is best-effort and may coalesce;
     * callers must not treat it as a transaction log.
     */
    Disposable watch(String path, boolean recursive, Consumer<Change> listener);

    /**
     * Whether this filesystem can be reached right now. Used by health checks and by the
     * workspace feature before declaring a workspace open.
     */
    default boolean isAvailable() {
        return true;
    }
}
