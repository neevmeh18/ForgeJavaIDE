package dev.forge.filesystem;

import dev.forge.core.WorkspaceId;
import dev.forge.core.event.Event;

public record FileMoved(WorkspaceId workspaceId, String from, String to) implements Event {
    @Override
    public String type() {
        return "file.moved";
    }
}
