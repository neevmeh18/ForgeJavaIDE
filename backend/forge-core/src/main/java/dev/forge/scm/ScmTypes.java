package dev.forge.scm;

import java.time.Instant;
import java.util.List;

/**
 * Generic source-control vocabulary.
 *
 * <p>Nothing here is Git-specific. Git is the first implementation, not the model: rebases,
 * submodules, worktrees and reflogs stay inside the Git provider, and what surfaces is the
 * subset every source-control system shares — changes, staging, commits, branches, diffs.
 */
public final class ScmTypes {

    private ScmTypes() {
    }

    public enum ChangeStatus {
        ADDED,
        MODIFIED,
        DELETED,
        RENAMED,
        UNTRACKED,
        CONFLICTED
    }

    /** One changed path. {@code staged} distinguishes the index from the working tree. */
    public record Change(String path, ChangeStatus status, boolean staged, String originalPath) {
    }

    public record Branch(String name, boolean current, boolean remote) {
    }

    public record Commit(String id, String shortId, String message, String author, Instant date) {
    }

    /**
     * A repository snapshot. {@code ahead}/{@code behind} are -1 when there is no upstream to
     * compare against, which is a real state and not an error.
     */
    public record RepositoryStatus(
            boolean repository,
            String branch,
            int ahead,
            int behind,
            boolean clean,
            List<Change> changes,
            boolean conflicted) {

        public static final RepositoryStatus NONE =
                new RepositoryStatus(false, "", -1, -1, true, List.of(), false);

        public RepositoryStatus {
            changes = changes == null ? List.of() : List.copyOf(changes);
        }
    }

    /** A unified diff for one path. Rendering is the frontend's problem. */
    public record Diff(String path, String text, boolean staged) {
    }
}
