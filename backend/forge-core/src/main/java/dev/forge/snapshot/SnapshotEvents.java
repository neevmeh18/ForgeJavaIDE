package dev.forge.snapshot;

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
}
