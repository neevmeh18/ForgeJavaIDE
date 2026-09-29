package dev.forge.filesystem;

import dev.forge.core.WorkspaceId;
import dev.forge.core.event.Event;

public record FileDeleted(WorkspaceId workspaceId, String path) implements Event {
    @Override
    public String type() {
        return "file.deleted";
    }
}
