package dev.forge.core.command;

import dev.forge.core.Ids.SessionId;
import dev.forge.core.Ids.WorkspaceId;
import dev.forge.core.event.Event;

/**
 * Events describing command execution itself.
 *
 * <p>They exist so a caller that started a long-running command asynchronously can learn how it
 * ended without polling, and so audit or automation clients can watch activity. They are
 * notifications about what already happened — never a way to ask for a command to run.
 */
public final class CommandEvents {

    private CommandEvents() {
    }

    public record CommandStarted(String executionId, CommandId commandId, SessionId sessionId, WorkspaceId workspaceId)
            implements Event {
        @Override
        public String type() {
            return "command.started";
        }
    }

    public record CommandCompleted(String executionId, CommandId commandId, Object result,
                                   SessionId sessionId, WorkspaceId workspaceId) implements Event {
        @Override
        public String type() {
            return "command.completed";
        }
    }

    public record CommandFailed(String executionId, CommandId commandId, String code, String message,
                                SessionId sessionId, WorkspaceId workspaceId) implements Event {
        @Override
        public String type() {
            return "command.failed";
        }
    }
}
