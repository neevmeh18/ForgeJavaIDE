package dev.forge.filesystem;

import dev.forge.core.WorkspaceId;
import dev.forge.core.event.Event;

public record FileCreated(WorkspaceId workspaceId, String path, boolean directory) implements Event {
    @Override
    public String type() {
        return "file.created";
    }
}
