package dev.forge.filesystem;

import dev.forge.core.WorkspaceId;
import dev.forge.core.event.Event;

public record FileSaved(WorkspaceId workspaceId, String path, long size) implements Event {
    @Override
    public String type() {
        return "file.saved";
    }
}
