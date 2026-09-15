package dev.forge.tasks;

import dev.forge.core.ForgeException;
import dev.forge.core.Ids;
import dev.forge.core.Ids.TaskExecutionId;
import dev.forge.core.Ids.TerminalId;
import dev.forge.core.Ids.WorkspaceId;
import dev.forge.core.Lifecycle;
import dev.forge.core.Log;
import dev.forge.core.event.EventBus;
import dev.forge.terminal.TerminalEvents;
import dev.forge.terminal.TerminalService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Discovers tasks and runs them.
 *
 * <p>The task engine owns the lifecycle — {@code RUNNING}, {@code SUCCEEDED}, {@code FAILED},
 * {@code CANCELLED} — and borrows a terminal purely for presentation. Correlating a terminal's
 * exit with a task execution happens here, through events, so the terminal feature knows
 * nothing about tasks and could be swapped for a different presentation entirely.
 */
public final class TaskService implements Lifecycle.Component {

    private static final Log log = Log.of(TaskService.class);

    public enum State {
        RUNNING,
        SUCCEEDED,
        FAILED,
        CANCELLED
    }

    public record Execution(
            TaskExecutionId id,
            String taskId,
            String name,
            WorkspaceId workspaceId,
            TerminalId terminalId,
            State state,
            int exitCode,
            Instant startedAt) {

        Execution finished(State newState, int code) {
            return new Execution(id, taskId, name, workspaceId, terminalId, newState, code, startedAt);
        }
    }

    private final Map<TaskExecutionId, Execution> executions = new ConcurrentHashMap<>();
    private final Map<TerminalId, TaskExecutionId> byTerminal = new ConcurrentHashMap<>();
    private final List<TaskProvider> providers;
    private final TerminalService terminals;
    private final EventBus events;
    private final Lifecycle.Store subscriptions = new Lifecycle.Store();

    public TaskService(List<TaskProvider> providers, TerminalService terminals, EventBus events) {
        this.providers = List.copyOf(providers);
        this.terminals = terminals;
        this.events = events;
    }

    @Override
    public void start() {
        subscriptions.add(events.subscribe(TerminalEvents.TerminalExited.class, this::onTerminalExited));
    }

    @Override
    public void dispose() {
        subscriptions.dispose();
    }

    /** Every task any provider offers for this workspace. */
    public List<Task> available(WorkspaceId workspace) {
        List<Task> tasks = new ArrayList<>();
        for (TaskProvider provider : providers) {
            try {
                tasks.addAll(provider.provide(workspace));
            } catch (RuntimeException e) {
                log.with("provider", provider.id()).warn("Task discovery failed", e);
            }
        }
        tasks.sort(Comparator.comparing(Task::name));
        return tasks;
    }

    public Execution run(WorkspaceId workspace, String taskId) {
        Task task = available(workspace).stream()
                .filter(candidate -> candidate.id().equals(taskId))
                .findFirst()
                .orElseThrow(() -> ForgeException.notFound("Unknown task: " + taskId).with("taskId", taskId));

        TerminalService.TerminalInfo terminal = task.shell()
                ? terminals.run(workspace, "/bin/sh", List.of("-c", commandLine(task)), task.cwd(),
                        task.name(), task.env())
                : terminals.run(workspace, task.executable(), task.arguments(), task.cwd(),
                        task.name(), task.env());

        Execution execution = new Execution(TaskExecutionId.of(Ids.random("task")), task.id(), task.name(),
                workspace, terminal.id(), State.RUNNING, -1, Instant.now());
        executions.put(execution.id(), execution);
        byTerminal.put(terminal.id(), execution.id());
        log.with("workspaceId", workspace).with("taskId", task.id()).info("Task started");
        events.publish(new TaskEvents.TaskStarted(workspace, execution.id(), task.id(), task.name()));
        return execution;
    }

    public void cancel(TaskExecutionId id, WorkspaceId workspace) {
        Execution execution = require(id, workspace);
        if (execution.state() == State.RUNNING) {
            terminals.kill(execution.terminalId(), workspace);
        }
    }

    public List<Execution> executions(WorkspaceId workspace) {
        return executions.values().stream()
                .filter(execution -> execution.workspaceId().equals(workspace))
                .sorted(Comparator.comparing(Execution::startedAt).reversed())
                .toList();
    }

    public Execution require(TaskExecutionId id, WorkspaceId workspace) {
        return Optional.ofNullable(executions.get(id))
                .filter(execution -> execution.workspaceId().equals(workspace))
                .orElseThrow(() -> ForgeException.notFound("Unknown task execution: " + id));
    }

    private void onTerminalExited(TerminalEvents.TerminalExited event) {
        TaskExecutionId id = byTerminal.remove(event.terminalId());
        if (id == null) {
            return;
        }
        Execution execution = executions.get(id);
        if (execution == null) {
            return;
        }
        State state = switch (event.exitCode()) {
            case 0 -> State.SUCCEEDED;
            case 130, 137, 143 -> State.CANCELLED;
            default -> State.FAILED;
        };
        Execution finished = execution.finished(state, event.exitCode());
        executions.put(id, finished);
        log.with("taskId", finished.taskId()).with("state", state).info("Task finished");
        events.publish(new TaskEvents.TaskFinished(finished.workspaceId(), id, finished.taskId(),
                state.name(), event.exitCode()));
    }

    /** Quotes arguments so a shell task cannot be broken by a space in a path. */
    private static String commandLine(Task task) {
        StringBuilder line = new StringBuilder(task.executable());
        for (String argument : task.arguments()) {
            line.append(" '").append(argument.replace("'", "'\\''")).append('\'');
        }
        return line.toString();
    }
}
