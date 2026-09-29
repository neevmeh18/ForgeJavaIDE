package dev.forge.terminal;

import dev.forge.core.TerminalId;
import dev.forge.core.WorkspaceId;
import dev.forge.core.event.Event;

public record TerminalExited(WorkspaceId workspaceId, TerminalId terminalId, int exitCode) implements Event {
    @Override
    public String type() {
        return "terminal.exited";
    }
}
