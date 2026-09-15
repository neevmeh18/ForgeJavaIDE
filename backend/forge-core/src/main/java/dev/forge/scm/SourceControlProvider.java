package dev.forge.scm;

import dev.forge.core.Ids.WorkspaceId;
import dev.forge.scm.ScmTypes.Branch;
import dev.forge.scm.ScmTypes.Commit;
import dev.forge.scm.ScmTypes.Diff;
import dev.forge.scm.ScmTypes.RepositoryStatus;
import java.util.List;

/**
 * Source control for a workspace.
 *
 * <p>The provider decides how to reach the repository — a Git binary, a library, a hosted API —
 * and is the only place that knows. Everything above it works in {@link ScmTypes} terms, so a
 * Mercurial, Fossil or server-side provider needs no changes anywhere else.
 */
public interface SourceControlProvider {

    String id();

    /** Cheap check used to decide whether to show source-control UI at all. */
    boolean isRepository(WorkspaceId workspace);

    RepositoryStatus status(WorkspaceId workspace);

    void stage(WorkspaceId workspace, List<String> paths);

    void unstage(WorkspaceId workspace, List<String> paths);

    /** Discards working-tree changes. Destructive and not undoable — callers must confirm. */
    void discard(WorkspaceId workspace, List<String> paths);

    Commit commit(WorkspaceId workspace, String message, boolean amend);

    List<Branch> branches(WorkspaceId workspace);

    void checkout(WorkspaceId workspace, String branch, boolean create);

    Diff diff(WorkspaceId workspace, String path, boolean staged);

    List<Commit> history(WorkspaceId workspace, String path, int limit);

    /** Long-running network operations. Run as commands so they can be cancelled. */
    void fetch(WorkspaceId workspace);

    void pull(WorkspaceId workspace);

    void push(WorkspaceId workspace);
}
