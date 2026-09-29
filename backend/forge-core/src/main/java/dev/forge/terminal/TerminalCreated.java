package dev.forge.terminal;

import dev.forge.core.TerminalId;
import dev.forge.core.WorkspaceId;
import dev.forge.core.event.Event;

public record TerminalCreated(WorkspaceId workspaceId, TerminalId terminalId, String title) implements Event {
    @Override
    public String type() {
        return "terminal.created";
    }
}
