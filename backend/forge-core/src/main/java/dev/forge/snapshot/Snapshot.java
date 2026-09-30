package dev.forge.snapshot;

import dev.forge.core.Ids.UserId;
import dev.forge.core.Ids.WorkspaceId;
import java.time.Instant;

/** A durable copy of a workspace at a point in time. */
public record Snapshot(
        String id,
        UserId ownerId,
        WorkspaceId sourceWorkspaceId,
        String name,
        Instant createdAt,
        long fileCount,
        long totalBytes) {
}
