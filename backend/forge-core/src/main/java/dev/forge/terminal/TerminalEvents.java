package dev.forge.terminal;

import dev.forge.core.Ids.TerminalId;
import dev.forge.core.Ids.WorkspaceId;
import dev.forge.core.event.Event;

/**
 * Terminal occurrences.
 *
 * <p>Scoped to the workspace rather than to one session, so that a shared terminal — several
 * people watching the same build — needs no new event plumbing when it arrives.
 */
public final class TerminalEvents {

    private TerminalEvents() {
    }

    public record TerminalCreated(WorkspaceId workspaceId, TerminalId terminalId, String title) implements Event {
        @Override
        public String type() {
            return "terminal.created";
        }
    }

    public record TerminalOutput(WorkspaceId workspaceId, TerminalId terminalId, String data) implements Event {
        @Override
        public String type() {
            return "terminal.output";
        }
    }

    public record TerminalExited(WorkspaceId workspaceId, TerminalId terminalId, int exitCode) implements Event {
        @Override
        public String type() {
            return "terminal.exited";
        }
    }
}
