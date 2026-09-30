package dev.forge.tasklog;

import dev.forge.core.ForgeException;
import dev.forge.core.Ids.TaskExecutionId;
import dev.forge.core.Ids.TerminalId;
import dev.forge.core.Ids.WorkspaceId;
import dev.forge.core.Lifecycle;
import dev.forge.core.Log;
import dev.forge.core.event.EventBus;
import dev.forge.tasks.TaskEvents;
import dev.forge.tasks.TaskService;
import dev.forge.terminal.TerminalEvents;
import dev.forge.workspace.WorkspaceEvents;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.ConcurrentHashMap;

public final class TaskLogService implements Lifecycle.Component {
    private static final Log log = Log.of(TaskLogService.class);

    public record Summary(String executionId, String taskId, String name, String state,
                          int recordCount, Instant startedAt) { }

    private record Run(WorkspaceId workspaceId, TaskExecutionId executionId, TerminalId terminalId) { }

    private final TaskService tasks;
    private final EventBus events;
    private final TaskLogStore store;
    private final Map<TaskExecutionId, Run> runs = new ConcurrentHashMap<>();
    private final Map<TerminalId, TaskExecutionId> byTerminal = new ConcurrentHashMap<>();
    private final Lifecycle.Store subscriptions = new Lifecycle.Store();

    public TaskLogService(TaskService tasks, EventBus events) {
        this(tasks, events, new TaskLogStore(
                Path.of(System.getProperty("java.io.tmpdir"), "forge-task-logs"),
                System.getenv().getOrDefault("FORGE_TASK_LOG_INDEX_UNIT", "characters")));
    }

    TaskLogService(TaskService tasks, EventBus events, TaskLogStore store) {
        this.tasks = tasks;
        this.events = events;
        this.store = store;
    }

    @Override
    public void start() {
        store.start();
        subscriptions.add(events.subscribe(TaskEvents.TaskStarted.class, this::onStarted));
        subscriptions.add(events.subscribe(TerminalEvents.TerminalOutput.class, this::onOutput));
        subscriptions.add(events.subscribe(TaskEvents.TaskFinished.class, this::onFinished));
        subscriptions.add(events.subscribe(WorkspaceEvents.WorkspaceClosed.class, event -> {
            runs.values().removeIf(run -> {
                if (!run.workspaceId().equals(event.workspaceId())) return false;
                byTerminal.remove(run.terminalId());
                store.remove(run.executionId());
                return true;
            });
        }));
    }

    @Override
    public void dispose() {
        subscriptions.dispose();
        byTerminal.clear();
        runs.clear();
    }

    public List<Summary> summaries(WorkspaceId workspaceId) {
        return tasks.executions(workspaceId).stream()
                .map(execution -> new Summary(execution.id().value(), execution.taskId(), execution.name(),
                        execution.state().name(), store.visibleCount(execution.id()), execution.startedAt()))
                .sorted(Comparator.comparing(Summary::startedAt).reversed())
                .toList();
    }

    public List<Map<String, Object>> records(WorkspaceId workspaceId, TaskExecutionId executionId) {
        tasks.require(executionId, workspaceId);
        Run run = Optional.ofNullable(runs.get(executionId))
                .filter(value -> value.workspaceId().equals(workspaceId))
                .orElseThrow(() -> ForgeException.notFound("Task log is not available: " + executionId));
        return store.records(run.executionId());
    }

    private void onStarted(TaskEvents.TaskStarted event) {
        TaskService.Execution execution = tasks.require(event.executionId(), event.workspaceId());
        try {
            store.create(event.executionId());
            Run run = new Run(event.workspaceId(), event.executionId(), execution.terminalId());
            runs.put(event.executionId(), run);
            byTerminal.put(execution.terminalId(), event.executionId());

            Map<String, Object> runtime = new LinkedHashMap<>();
            runtime.put("type", "runtime");
            tasks.invocation(event.executionId(), event.workspaceId()).ifPresent(invocation -> {
                if (invocation.userId() != null) runtime.put("userId", invocation.userId().value());
                if (invocation.sessionId() != null) runtime.put("sessionId", invocation.sessionId().value());
            });
            runtime.put("createdAt", Instant.now().toString());
            store.writeRuntime(event.executionId(), runtime);

            Map<String, Object> started = new LinkedHashMap<>();
            started.put("type", "started");
            started.put("taskId", event.taskId());
            started.put("name", event.name());
            started.put("timestamp", Instant.now().toString());
            store.append(event.executionId(), started, true);
            pruneWorkspace(event.workspaceId());
        } catch (IOException e) {
            runs.remove(event.executionId());
            byTerminal.remove(execution.terminalId());
            store.remove(event.executionId());
            log.with("executionId", event.executionId()).warn("Task log could not be created", e);
        }
    }

    private void onOutput(TerminalEvents.TerminalOutput event) {
        TaskExecutionId executionId = byTerminal.get(event.terminalId());
        if (executionId == null) return;
        Run run = runs.get(executionId);
        if (run == null || !run.workspaceId().equals(event.workspaceId())) return;
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("type", "output");
        record.put("stream", "stdout");
        record.put("timestamp", Instant.now().toString());
        record.put("message", event.data());
        append(executionId, record);
    }

    private void onFinished(TaskEvents.TaskFinished event) {
        Run run = runs.get(event.executionId());
        if (run == null || !run.workspaceId().equals(event.workspaceId())) return;
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("type", "finished");
        record.put("taskId", event.taskId());
        TaskService.Execution execution = tasks.require(event.executionId(), event.workspaceId());
        record.put("name", execution.name());
        record.put("state", event.state());
        record.put("exitCode", event.exitCode());
        record.put("timestamp", Instant.now().toString());
        append(event.executionId(), record);
        byTerminal.remove(run.terminalId());
    }


    private void pruneWorkspace(WorkspaceId workspaceId) {
        Set<TaskExecutionId> retained = tasks.executions(workspaceId).stream()
                .map(TaskService.Execution::id)
                .collect(Collectors.toSet());
        runs.values().removeIf(run -> {
            if (!run.workspaceId().equals(workspaceId) || retained.contains(run.executionId())) return false;
            byTerminal.remove(run.terminalId());
            store.remove(run.executionId());
            return true;
        });
    }

    private void append(TaskExecutionId executionId, Map<String, Object> record) {
        try {
            store.append(executionId, record, true);
        } catch (IOException e) {
            log.with("executionId", executionId).warn("Task log update failed", e);
        }
    }
}
