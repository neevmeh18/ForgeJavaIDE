package dev.forge.snapshot;

import dev.forge.core.ForgeException;
import dev.forge.core.Ids.UserId;
import dev.forge.core.Ids.WorkspaceId;
import dev.forge.core.event.EventBus;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
        return create(owner, workspace, name, List.of(), 0);
    }

    public Snapshot create(UserId owner, WorkspaceId workspace, String name,
                           List<String> labels, int retentionDays) {
        String displayName = normalizedName(name);
        Snapshot snapshot = store.create(owner, workspace, displayName,
                normalizedLabels(labels), expiration(retentionDays));
        events.publish(new SnapshotEvents.SnapshotCreated(workspace, snapshot.id(), snapshot.name()));
        return snapshot;
    }

    public List<Snapshot> list(UserId owner, WorkspaceId workspace) {
        store.pruneExpired(owner, workspace, Instant.now());
        return store.list(owner, workspace);
    }

    public Snapshot update(UserId owner, WorkspaceId workspace, String snapshotId,
                           String name, List<String> labels, int retentionDays) {
        Snapshot snapshot = requireSource(store.find(owner, snapshotId), workspace);
        Snapshot updated = store.update(owner, snapshot.id(), normalizedName(name),
                normalizedLabels(labels), expiration(retentionDays));
        events.publish(new SnapshotEvents.SnapshotUpdated(workspace, updated.id()));
        return updated;
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

    private static List<String> normalizedLabels(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        if (values.size() > 8) {
            throw ForgeException.invalidArgument("A snapshot can have at most 8 labels");
        }
        return values.stream()
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .peek(value -> {
                    if (value.length() > 24 || !value.matches("[A-Za-z0-9._-]+")) {
                        throw ForgeException.invalidArgument("Invalid snapshot label");
                    }
                })
                .distinct()
                .sorted()
                .toList();
    }

    private static Instant expiration(int retentionDays) {
        if (retentionDays < 0 || retentionDays > 365) {
            throw ForgeException.invalidArgument("Retention must be between 0 and 365 days");
        }
        return retentionDays == 0 ? null : Instant.now().plus(retentionDays, ChronoUnit.DAYS);
    }
}
