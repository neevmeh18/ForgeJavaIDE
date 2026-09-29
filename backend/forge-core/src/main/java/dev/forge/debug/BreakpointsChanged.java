package dev.forge.debug;

import dev.forge.core.DebugSessionId;
import dev.forge.core.WorkspaceId;
import dev.forge.core.event.Event;
import dev.forge.debug.Breakpoint;

public record BreakpointsChanged(WorkspaceId workspaceId, String path, java.util.List<Breakpoint> breakpoints)
        implements Event {
    @Override
    public String type() {
        return "debug.breakpointsChanged";
    }
}
