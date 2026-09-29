package dev.forge.terminal;

import dev.forge.core.TerminalId;
import dev.forge.core.WorkspaceId;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

public record Spec(
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
