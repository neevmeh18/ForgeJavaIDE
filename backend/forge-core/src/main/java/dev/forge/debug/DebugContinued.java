package dev.forge.debug;

import dev.forge.core.DebugSessionId;
import dev.forge.core.WorkspaceId;
import dev.forge.core.event.Event;
import dev.forge.debug.Breakpoint;

public record DebugContinued(WorkspaceId workspaceId, DebugSessionId debugSessionId, int threadId) implements Event {
    @Override
    public String type() {
        return "debug.continued";
    }
}
