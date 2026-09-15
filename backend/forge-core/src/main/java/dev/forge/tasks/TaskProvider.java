package dev.forge.tasks;

import dev.forge.core.Ids.WorkspaceId;
import java.util.List;

/**
 * Discovers the tasks available in a workspace.
 *
 * <p>Several providers can coexist — one reading the workspace's own task file, others
 * contributed by extensions for a build tool they understand. None of them runs anything;
 * running is {@link TaskService}'s job, which is what keeps task execution uniform regardless
 * of who described the task.
 */
public interface TaskProvider {

    String id();

    List<Task> provide(WorkspaceId workspace);
}
