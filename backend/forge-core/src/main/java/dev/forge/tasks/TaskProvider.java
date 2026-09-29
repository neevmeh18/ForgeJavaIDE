package dev.forge.tasks;

import dev.forge.core.WorkspaceId;
import java.util.List;









public interface TaskProvider {

    String id();

    List<Task> provide(WorkspaceId workspace);
}
