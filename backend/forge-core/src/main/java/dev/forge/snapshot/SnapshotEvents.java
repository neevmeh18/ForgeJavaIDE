package dev.forge.snapshot;

import dev.forge.core.Ids.UserId;
import dev.forge.core.Ids.WorkspaceId;
import dev.forge.core.event.Event;

/** Completed snapshot operations. */
public final class SnapshotEvents {

    private SnapshotEvents() {
    }

    public record SnapshotCreated(WorkspaceId workspaceId, String snapshotId, String name) implements Event {
        @Override
        public String type() {
            return "snapshot.created";
        }
    }

    public record SnapshotRestored(WorkspaceId workspaceId, String snapshotId) implements Event {
        @Override
        public String type() {
            return "snapshot.restored";
        }
    }

    public record SnapshotDeleted(WorkspaceId workspaceId, String snapshotId) implements Event {
        @Override
        public String type() {
            return "snapshot.deleted";
        }
    }

    public record SnapshotUpdated(WorkspaceId workspaceId, String snapshotId) implements Event {
        @Override
        public String type() {
            return "snapshot.updated";
        }
    }

    public record ShareCreated(WorkspaceId workspaceId, String shareId, String snapshotId) implements Event {
        @Override
        public String type() {
            return "snapshot.shareCreated";
        }
    }

    public record ShareRevoked(WorkspaceId workspaceId, String shareId) implements Event {
        @Override
        public String type() {
            return "snapshot.shareRevoked";
        }
    }

    public record SharedSnapshotImported(
            WorkspaceId workspaceId,
            String shareId,
            String snapshotId,
            UserId importedBy) implements Event {
        @Override
        public String type() {
            return "snapshot.sharedImported";
        }
    }
}
