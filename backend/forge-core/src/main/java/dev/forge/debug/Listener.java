package dev.forge.debug;

import dev.forge.core.DebugSessionId;
import dev.forge.core.WorkspaceId;
import dev.forge.debug.Breakpoint;
import dev.forge.debug.DebugConfiguration;
import dev.forge.debug.StackFrame;
import dev.forge.debug.ThreadInfo;
import dev.forge.debug.Variable;
import java.util.List;

public interface Listener {
    void stopped(DebugSessionId session, int threadId, String reason);

    void continued(DebugSessionId session, int threadId);

    void output(DebugSessionId session, String category, String text);

    void terminated(DebugSessionId session, int exitCode);
}
