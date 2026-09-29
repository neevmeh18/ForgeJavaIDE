package dev.forge.debug;

import dev.forge.core.DebugSessionId;
import dev.forge.core.WorkspaceId;
import dev.forge.debug.Breakpoint;
import dev.forge.debug.DebugConfiguration;
import dev.forge.debug.StackFrame;
import dev.forge.debug.ThreadInfo;
import dev.forge.debug.Variable;
import java.util.List;








public interface DebugAdapter {


    String type();





    void start(DebugSessionId session, WorkspaceId workspace, DebugConfiguration configuration, Listener listener);


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
