package dev.forge.core.command;

import dev.forge.core.SessionId;
import dev.forge.core.WorkspaceId;
import dev.forge.core.event.Event;

public record CommandCompleted(String executionId, CommandId commandId, Object result,
                               SessionId sessionId, WorkspaceId workspaceId) implements Event {
    @Override
    public String type() {
        return "command.completed";
    }
}
