package dev.forge.snapshot;

import dev.forge.core.ForgeException;
import dev.forge.core.Ids.UserId;
import dev.forge.core.Ids.WorkspaceId;
import dev.forge.core.event.EventBus;
import java.util.List;

/** Creates and restores durable workspace snapshots. */
public final class SnapshotService {

    private final SnapshotStore store;
    private final EventBus events;

    public SnapshotService(SnapshotStore store, EventBus events) {
        this.store = store;
        this.events = events;
    }

    public Snapshot create(UserId owner, WorkspaceId workspace, String name) {
        String displayName = normalizedName(name);
        Snapshot snapshot = store.create(owner, workspace, displayName);
        events.publish(new SnapshotEvents.SnapshotCreated(workspace, snapshot.id(), snapshot.name()));
        return snapshot;
    }

    public List<Snapshot> list(UserId owner, WorkspaceId workspace) {
        return store.list(owner, workspace);
    }

    public Snapshot restore(UserId owner, WorkspaceId sourceWorkspace, String snapshotId,
                            WorkspaceId targetWorkspace) {
        Snapshot snapshot = requireSource(store.find(owner, snapshotId), sourceWorkspace);
        Snapshot restored = store.restore(owner, snapshot.id(), targetWorkspace);
        events.publish(new SnapshotEvents.SnapshotRestored(targetWorkspace, restored.id()));
        return restored;
    }

    public void delete(UserId owner, WorkspaceId workspace, String snapshotId) {
        Snapshot snapshot = requireSource(store.find(owner, snapshotId), workspace);
        store.delete(owner, snapshot.id());
        events.publish(new SnapshotEvents.SnapshotDeleted(workspace, snapshot.id()));
    }

    private static Snapshot requireSource(Snapshot snapshot, WorkspaceId workspace) {
        if (!snapshot.sourceWorkspaceId().equals(workspace)) {
            throw ForgeException.notFound("Unknown snapshot: " + snapshot.id())
                    .with("snapshotId", snapshot.id());
        }
        return snapshot;
    }

    private static String normalizedName(String value) {
        String name = value == null ? "" : value.strip();
        if (name.isEmpty()) {
            return "Workspace snapshot";
        }
        if (name.length() > 80 || name.chars().anyMatch(Character::isISOControl)) {
            throw ForgeException.invalidArgument("Invalid snapshot name");
        }
        return name;
    }
}
