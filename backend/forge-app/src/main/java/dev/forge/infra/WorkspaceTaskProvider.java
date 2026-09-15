package dev.forge.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.forge.core.Ids.WorkspaceId;
import dev.forge.core.Log;
import dev.forge.filesystem.FileSystem;
import dev.forge.tasks.Task;
import dev.forge.tasks.TaskProvider;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tasks declared by the workspace itself, in {@code .forge/tasks.json}.
 *
 * <p>Read through the {@link FileSystem} capability rather than from a local path, so a
 * workspace on a remote host or in a container publishes its tasks the same way a local folder
 * does — the point of having a filesystem abstraction at all.
 *
 * <p>Shape:
 * <pre>
 * {
 *   "tasks": [
 *     {"id": "build", "name": "Build", "type": "build",
 *      "command": "mvn", "args": ["-B", "package"], "cwd": "", "shell": false}
 *   ]
 * }
 * </pre>
 */
public final class WorkspaceTaskProvider implements TaskProvider {

    private static final Log log = Log.of(WorkspaceTaskProvider.class);
    private static final String TASK_FILE = ".forge/tasks.json";
    private static final long MAX_BYTES = 256 * 1024;

    private final ObjectMapper mapper = new ObjectMapper();
    private final FileSystem.Locator locator;

    public WorkspaceTaskProvider(FileSystem.Locator locator) {
        this.locator = locator;
    }

    @Override
    public String id() {
        return "workspace";
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Task> provide(WorkspaceId workspace) {
        FileSystem fs;
        try {
            fs = locator.forWorkspace(workspace);
            if (!fs.exists(TASK_FILE)) {
                return List.of();
            }
        } catch (RuntimeException e) {
            return List.of();
        }
        try {
            Map<String, Object> document = mapper.readValue(fs.read(TASK_FILE, MAX_BYTES), Map.class);
            Object declared = document.get("tasks");
            if (!(declared instanceof List<?> entries)) {
                return List.of();
            }
            List<Task> tasks = new ArrayList<>();
            for (Object entry : entries) {
                if (entry instanceof Map<?, ?> fields) {
                    tasks.add(toTask((Map<String, Object>) fields));
                }
            }
            return List.copyOf(tasks);
        } catch (IOException | RuntimeException e) {
            log.with("workspaceId", workspace).warn("Ignoring unreadable " + TASK_FILE);
            return List.of();
        }
    }

    private static Task toTask(Map<String, Object> fields) {
        String command = string(fields, "command", "");
        List<String> arguments = new ArrayList<>();
        if (fields.get("args") instanceof List<?> declared) {
            declared.forEach(argument -> arguments.add(String.valueOf(argument)));
        }
        Map<String, String> env = new LinkedHashMap<>();
        if (fields.get("env") instanceof Map<?, ?> declared) {
            declared.forEach((key, value) -> env.put(String.valueOf(key), String.valueOf(value)));
        }
        return new Task(
                string(fields, "id", command),
                string(fields, "name", command),
                string(fields, "type", Task.TYPE_RUN),
                command,
                List.copyOf(arguments),
                string(fields, "cwd", ""),
                Map.copyOf(env),
                Boolean.TRUE.equals(fields.get("shell")),
                "workspace");
    }

    private static String string(Map<String, Object> fields, String key, String fallback) {
        Object value = fields.get(key);
        return value instanceof String text && !text.isBlank() ? text : fallback;
    }
}
