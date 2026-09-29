package dev.forge.tasks;

import dev.forge.core.TaskExecutionId;
import dev.forge.core.WorkspaceId;
import dev.forge.core.event.Event;

public record TaskFinished(WorkspaceId workspaceId, TaskExecutionId executionId, String taskId,
                           String state, int exitCode) implements Event {
    @Override
    public String type() {
        return "task.finished";
    }
}
