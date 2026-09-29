package dev.forge.workspace;

import dev.forge.core.WorkspaceId;
import java.time.Instant;
import java.util.Map;










public record Workspace(
        WorkspaceId id,
        String name,
        Location location,
        Map<String, String> metadata,
        State state,
        Instant openedAt) {









    public Workspace {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public Workspace withState(State newState) {
        return new Workspace(id, name, location, metadata, newState,
                newState == State.OPEN ? Instant.now() : openedAt);
    }
}
