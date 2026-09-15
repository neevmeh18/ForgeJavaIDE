package dev.forge.terminal;

import dev.forge.core.Ids.TerminalId;
import dev.forge.core.Ids.WorkspaceId;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Starts terminal processes. A privileged capability, and deliberately the only route to one.
 *
 * <p>Implementations decide where the process runs: a child process of the server, a container
 * exec, an SSH channel. Keeping that behind this interface is what stops "run a command" from
 * hard-coding the assumption that the workspace is a local directory.
 */
public interface TerminalProvider {

    /**
     * What to start. {@code cwd} is workspace-relative and validated by {@link TerminalService}
     * before it ever reaches a provider; {@code env} is <em>additional</em> environment, not a
     * replacement for the provider's baseline.
     */
    record Spec(
            WorkspaceId workspace,
            TerminalId id,
            String shell,
            List<String> arguments,
            String cwd,
            Map<String, String> env,
            int columns,
            int rows) {

        public Spec {
            arguments = arguments == null ? List.of() : List.copyOf(arguments);
            env = env == null ? Map.of() : Map.copyOf(env);
            columns = Math.clamp(columns, 20, 500);
            rows = Math.clamp(rows, 5, 200);
        }
    }

    /**
     * Creates and starts a session. Output arrives on {@code onOutput} as decoded text;
     * {@code onExit} fires exactly once with the exit code.
     */
    TerminalSession create(Spec spec, Consumer<String> onOutput, IntConsumer onExit);

    /** Whether terminals can be started at all in this deployment. */
    default boolean isAvailable() {
        return true;
    }
}
