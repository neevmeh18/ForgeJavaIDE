package dev.forge.snapshot;

import dev.forge.core.Ids.UserId;
import dev.forge.core.Ids.WorkspaceId;
import java.util.List;

/** Persistence capability for workspace snapshots. */
public interface SnapshotStore {

    Snapshot create(UserId owner, WorkspaceId sourceWorkspace, String name);

    List<Snapshot> list(UserId owner, WorkspaceId sourceWorkspace);

    Snapshot find(UserId owner, String snapshotId);

    Snapshot restore(UserId owner, String snapshotId, WorkspaceId targetWorkspace);

    void delete(UserId owner, String snapshotId);
}
