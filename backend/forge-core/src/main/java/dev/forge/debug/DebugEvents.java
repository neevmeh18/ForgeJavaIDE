package dev.forge.debug;

import dev.forge.core.Ids.DebugSessionId;
import dev.forge.core.Ids.WorkspaceId;
import dev.forge.core.event.Event;
import dev.forge.debug.DebugTypes.Breakpoint;

/** Debugging occurrences. */
public final class DebugEvents {

    private DebugEvents() {
    }

    public record DebugSessionStarted(WorkspaceId workspaceId, DebugSessionId debugSessionId, String name, String type)
            implements Event {
        @Override
        public String type() {
            return "debug.sessionStarted";
        }
    }

    public record DebugStopped(WorkspaceId workspaceId, DebugSessionId debugSessionId, int threadId, String reason)
            implements Event {
        @Override
        public String type() {
            return "debug.stopped";
        }
    }

    public record DebugContinued(WorkspaceId workspaceId, DebugSessionId debugSessionId, int threadId) implements Event {
        @Override
        public String type() {
            return "debug.continued";
        }
    }

    public record DebugOutput(WorkspaceId workspaceId, DebugSessionId debugSessionId, String category, String text)
            implements Event {
        @Override
        public String type() {
            return "debug.output";
        }
    }

    public record DebugSessionEnded(WorkspaceId workspaceId, DebugSessionId debugSessionId, int exitCode)
            implements Event {
        @Override
        public String type() {
            return "debug.sessionEnded";
        }
    }

    public record BreakpointsChanged(WorkspaceId workspaceId, String path, java.util.List<Breakpoint> breakpoints)
            implements Event {
        @Override
        public String type() {
            return "debug.breakpointsChanged";
        }
    }
}
