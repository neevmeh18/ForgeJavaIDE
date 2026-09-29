package dev.forge.terminal;

import dev.forge.core.TerminalId;
import dev.forge.core.WorkspaceId;
import dev.forge.core.event.Event;

public record TerminalOutput(WorkspaceId workspaceId, TerminalId terminalId, String data) implements Event {
    @Override
    public String type() {
        return "terminal.output";
    }
}
