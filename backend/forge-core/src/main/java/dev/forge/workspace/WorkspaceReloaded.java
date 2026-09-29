package dev.forge.workspace;

import dev.forge.core.SessionId;
import dev.forge.core.WorkspaceId;
import dev.forge.core.event.Event;

public record WorkspaceReloaded(WorkspaceId workspaceId) implements Event {
    @Override
    public String type() {
        return "workspace.reloaded";
    }
}
