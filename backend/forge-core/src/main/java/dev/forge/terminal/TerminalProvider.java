package dev.forge.terminal;

import dev.forge.core.TerminalId;
import dev.forge.core.WorkspaceId;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntConsumer;








public interface TerminalProvider {












    TerminalSession create(Spec spec, Consumer<String> onOutput, IntConsumer onExit);


    default boolean isAvailable() {
        return true;
    }
}
