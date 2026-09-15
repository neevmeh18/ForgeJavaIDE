package dev.forge.tasks;

import dev.forge.core.Ids.TaskExecutionId;
import dev.forge.core.Ids.WorkspaceId;
import dev.forge.core.event.Event;

/** Task execution occurrences. */
public final class TaskEvents {

    private TaskEvents() {
    }

    public record TaskStarted(WorkspaceId workspaceId, TaskExecutionId executionId, String taskId, String name)
            implements Event {
        @Override
        public String type() {
            return "task.started";
        }
    }

    public record TaskFinished(WorkspaceId workspaceId, TaskExecutionId executionId, String taskId,
                               String state, int exitCode) implements Event {
        @Override
        public String type() {
            return "task.finished";
        }
    }
}
