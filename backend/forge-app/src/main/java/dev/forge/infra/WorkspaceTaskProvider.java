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
    private static final int MAX_TASKS = 128;
    private static final int MAX_ARGUMENTS = 128;
    private static final int MAX_ENV = 64;
    private static final int MAX_FIELD = 4096;

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
            if (entries.size() > MAX_TASKS) {
                log.with("workspaceId", workspace).warn("Ignoring task file with too many tasks");
                return List.of();
            }
            List<Task> tasks = new ArrayList<>();
            java.util.Set<String> ids = new java.util.HashSet<>();
            for (Object entry : entries) {
                if (entry instanceof Map<?, ?> fields) {
                    Task task = toTask((Map<String, Object>) fields);
                    if (!ids.add(task.id())) {
                        throw new IllegalArgumentException("Duplicate task id: " + task.id());
                    }
                    tasks.add(task);
                }
            }
            return List.copyOf(tasks);
        } catch (IOException | RuntimeException e) {
            log.with("workspaceId", workspace).warn("Ignoring unreadable " + TASK_FILE);
            return List.of();
        }
    }

    private static Task toTask(Map<String, Object> fields) {
        String command = string(fields, "command", "", true);
        if (command.isBlank()) {
            throw new IllegalArgumentException("Task command is required");
        }
        String id = string(fields, "id", "", true);
        if (id.isBlank() || !id.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new IllegalArgumentException("Task id is required and must be a simple identifier");
        }
        List<String> arguments = new ArrayList<>();
        if (fields.get("args") instanceof List<?> declared) {
            if (declared.size() > MAX_ARGUMENTS) {
                throw new IllegalArgumentException("Too many task arguments");
            }
            declared.forEach(argument -> arguments.add(limited(String.valueOf(argument))));
        }
        Map<String, String> env = new LinkedHashMap<>();
        if (fields.get("env") instanceof Map<?, ?> declared) {
            if (declared.size() > MAX_ENV) {
                throw new IllegalArgumentException("Too many task environment variables");
            }
            declared.forEach((key, value) -> env.put(limited(String.valueOf(key)), limited(String.valueOf(value))));
        }
        return new Task(
                id,
                string(fields, "name", id, false),
                string(fields, "type", Task.TYPE_RUN, false),
                command,
                List.copyOf(arguments),
                string(fields, "cwd", "", false),
                Map.copyOf(env),
                Boolean.TRUE.equals(fields.get("shell")),
                "workspace");
    }

    private static String string(Map<String, Object> fields, String key, String fallback, boolean allowBlank) {
        Object value = fields.get(key);
        if (!(value instanceof String text)) {
            return fallback;
        }
        String trimmed = limited(text).trim();
        return !allowBlank && trimmed.isBlank() ? fallback : trimmed;
    }

    private static String limited(String value) {
        if (value.length() > MAX_FIELD || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Task field is too large or invalid");
        }
        return value;
    }
}
