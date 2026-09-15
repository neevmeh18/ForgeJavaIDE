package dev.forge.workspace;

import dev.forge.core.Ids.SessionId;
import dev.forge.core.Ids.WorkspaceId;
import dev.forge.core.event.Event;

/** Completed workspace lifecycle occurrences. */
public final class WorkspaceEvents {

    private WorkspaceEvents() {
    }

    public record WorkspaceOpened(WorkspaceId workspaceId, String name) implements Event {
        @Override
        public String type() {
            return "workspace.opened";
        }
    }

    public record WorkspaceClosed(WorkspaceId workspaceId) implements Event {
        @Override
        public String type() {
            return "workspace.closed";
        }
    }

    public record WorkspaceReloaded(WorkspaceId workspaceId) implements Event {
        @Override
        public String type() {
            return "workspace.reloaded";
        }
    }

    /**
     * A session started working on a workspace. Several sessions may be attached at once —
     * the framework never assumes one workspace belongs to one user.
     */
    public record SessionAttached(WorkspaceId workspaceId, SessionId sessionId) implements Event {
        @Override
        public String type() {
            return "workspace.sessionAttached";
        }
    }

    public record SessionDetached(WorkspaceId workspaceId, SessionId sessionId) implements Event {
        @Override
        public String type() {
            return "workspace.sessionDetached";
        }
    }
}
