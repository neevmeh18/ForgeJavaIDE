package dev.forge.debug;

import java.util.List;
import java.util.Map;

/**
 * Debugging vocabulary, kept language-agnostic.
 *
 * <p>Shaped after the Debug Adapter Protocol because that is what debuggers already implement,
 * but expressed in framework types. Nothing here assumes the debuggee is a JVM — the framework
 * happens to be written in Java, which says nothing about what it can debug.
 */
public final class DebugTypes {

    private DebugTypes() {
    }

    /**
     * A breakpoint, owned by the workspace rather than by a session. Breakpoints outlive debug
     * sessions and are shared by everyone working on the environment.
     */
    public record Breakpoint(String id, String path, int line, boolean enabled, String condition, boolean verified) {
    }

    public record ThreadInfo(int id, String name, boolean stopped) {
    }

    public record StackFrame(int id, String name, String path, int line, int column) {
    }

    public record Variable(String name, String value, String type, int childrenReference) {
    }

    /** What a client needs to start a session: adapter type plus adapter-specific options. */
    public record DebugConfiguration(String name, String type, String request, Map<String, Object> options) {
        public DebugConfiguration {
            options = options == null ? Map.of() : Map.copyOf(options);
        }
    }

    public enum SessionState {
        STARTING,
        RUNNING,
        STOPPED,
        TERMINATED
    }

    public record SessionInfo(String id, String name, String type, SessionState state, List<ThreadInfo> threads) {
        public SessionInfo {
            threads = threads == null ? List.of() : List.copyOf(threads);
        }
    }
}
