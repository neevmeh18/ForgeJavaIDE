package dev.forge.debug;

import dev.forge.core.DebugSessionId;
import dev.forge.core.WorkspaceId;
import dev.forge.core.event.Event;
import dev.forge.debug.Breakpoint;

public record DebugStopped(WorkspaceId workspaceId, DebugSessionId debugSessionId, int threadId, String reason)
        implements Event {
    @Override
    public String type() {
        return "debug.stopped";
    }
}
