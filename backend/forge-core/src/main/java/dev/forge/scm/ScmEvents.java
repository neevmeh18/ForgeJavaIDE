package dev.forge.scm;

import dev.forge.core.Ids.WorkspaceId;
import dev.forge.core.event.Event;

/** Source-control occurrences. */
public final class ScmEvents {

    private ScmEvents() {
    }

    public record RepositoryChanged(WorkspaceId workspaceId, String branch, int changeCount) implements Event {
        @Override
        public String type() {
            return "scm.repositoryChanged";
        }
    }

    public record Committed(WorkspaceId workspaceId, String commitId, String message) implements Event {
        @Override
        public String type() {
            return "scm.committed";
        }
    }

    public record BranchChanged(WorkspaceId workspaceId, String branch) implements Event {
        @Override
        public String type() {
            return "scm.branchChanged";
        }
    }
}
