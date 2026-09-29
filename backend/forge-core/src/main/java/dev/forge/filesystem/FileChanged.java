package dev.forge.filesystem;

import dev.forge.core.WorkspaceId;
import dev.forge.core.event.Event;

public record FileChanged(WorkspaceId workspaceId, String path) implements Event {
    @Override
    public String type() {
        return "file.changed";
    }
}
