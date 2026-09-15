package dev.forge.debug;

import dev.forge.core.Ids.DebugSessionId;
import dev.forge.core.Ids.WorkspaceId;
import dev.forge.debug.DebugTypes.Breakpoint;
import dev.forge.debug.DebugTypes.DebugConfiguration;
import dev.forge.debug.DebugTypes.StackFrame;
import dev.forge.debug.DebugTypes.ThreadInfo;
import dev.forge.debug.DebugTypes.Variable;
import java.util.List;

/**
 * Drives a debuggee of one {@code type} ({@code node}, {@code python}, {@code jdwp}, …).
 *
 * <p>Provider-based on purpose: the IDE coordinates sessions, breakpoints and the views that
 * show them, and knows nothing about how any particular runtime is controlled. A Debug Adapter
 * Protocol client is one implementation of this interface, not a special case inside it.
 */
public interface DebugAdapter {

    /** The configuration {@code type} this adapter handles. */
    String type();

    /** Receives adapter-driven state changes; the service turns them into events. */
    interface Listener {
        void stopped(DebugSessionId session, int threadId, String reason);

        void continued(DebugSessionId session, int threadId);

        void output(DebugSessionId session, String category, String text);

        void terminated(DebugSessionId session, int exitCode);
    }

    /** Starts a debuggee. Must not block longer than it takes the adapter to acknowledge. */
    void start(DebugSessionId session, WorkspaceId workspace, DebugConfiguration configuration, Listener listener);

    /** Applies the full breakpoint set for one file; adapters replace rather than merge. */
    List<Breakpoint> setBreakpoints(DebugSessionId session, String path, List<Breakpoint> breakpoints);

    void resume(DebugSessionId session, int threadId);

    void pause(DebugSessionId session, int threadId);

    void stepOver(DebugSessionId session, int threadId);

    void stepInto(DebugSessionId session, int threadId);

    void stepOut(DebugSessionId session, int threadId);

    List<ThreadInfo> threads(DebugSessionId session);

    List<StackFrame> stackTrace(DebugSessionId session, int threadId);

    List<Variable> variables(DebugSessionId session, int frameId, int variablesReference);

    String evaluate(DebugSessionId session, int frameId, String expression);

    void stop(DebugSessionId session);
}
