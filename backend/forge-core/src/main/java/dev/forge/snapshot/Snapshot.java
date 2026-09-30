package dev.forge.snapshot;

import dev.forge.core.Ids.UserId;
import dev.forge.core.Ids.WorkspaceId;
import java.time.Instant;
import java.util.List;

/** A durable copy of a workspace at a point in time. */
public record Snapshot(
        String id,
        UserId ownerId,
        WorkspaceId sourceWorkspaceId,
        String name,
        Instant createdAt,
        Instant expiresAt,
        List<String> labels,
        long fileCount,
        long totalBytes) {

    public Snapshot {
        labels = labels == null ? List.of() : List.copyOf(labels);
    }

    public boolean expired(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }
}
