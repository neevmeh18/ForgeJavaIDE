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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Discovers tasks and tracks their bounded lifecycle. */
public final class TaskService implements Lifecycle.Component {

    private static final Log log = Log.of(TaskService.class);
    private static final int MAX_RETAINED_EXECUTIONS_PER_WORKSPACE = 200;

    public enum State { RUNNING, SUCCEEDED, FAILED, CANCELLED }

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
    private final Set<TaskExecutionId> cancellationRequested = ConcurrentHashMap.newKeySet();
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
        subscriptions.add(events.subscribe(dev.forge.workspace.WorkspaceEvents.WorkspaceClosed.class, event -> {
            executions.values().removeIf(value -> {
                if (!value.workspaceId().equals(event.workspaceId())) return false;
                byTerminal.remove(value.terminalId());
                cancellationRequested.remove(value.id());
                return true;
            });
        }));
        subscriptions.add(events.subscribe(TerminalEvents.TerminalExited.class,
                event -> finishTerminal(event.terminalId(), event.exitCode())));
    }

    @Override
    public void dispose() {
        subscriptions.dispose();
        byTerminal.clear();
        cancellationRequested.clear();
        executions.clear();
    }

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
                : terminals.run(workspace, task.executable(), task.arguments(), task.cwd(), task.name(), task.env());

        Execution execution = new Execution(TaskExecutionId.of(Ids.random("task")), task.id(), task.name(),
                workspace, terminal.id(), State.RUNNING, -1, Instant.now());
        executions.put(execution.id(), execution);
        byTerminal.put(terminal.id(), execution.id());
        prune(workspace);
        log.with("workspaceId", workspace).with("taskId", task.id()).info("Task started");
        events.publish(new TaskEvents.TaskStarted(workspace, execution.id(), task.id(), task.name()));

        // A process such as `true` may exit before the mapping above exists. TerminalService
        // retains a one-shot code so this check deterministically closes that race.
        var early = terminals.consumeExitCode(terminal.id());
        if (early.isPresent()) {
            finishTerminal(terminal.id(), early.getAsInt());
        }
        return executions.getOrDefault(execution.id(), execution);
    }

    public void cancel(TaskExecutionId id, WorkspaceId workspace) {
        Execution execution = require(id, workspace);
        if (execution.state() == State.RUNNING) {
            cancellationRequested.add(id);
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

    private void finishTerminal(TerminalId terminalId, int exitCode) {
        TaskExecutionId id = byTerminal.remove(terminalId);
        if (id == null) {
            // The process may have exited before run() installed the task mapping. Leave the
            // one-shot exit code in TerminalService so run() can consume it immediately after
            // registration and finish the task deterministically.
            return;
        }
        terminals.consumeExitCode(terminalId); // the event path won; the cache is no longer needed
        Execution execution = executions.get(id);
        if (execution == null || execution.state() != State.RUNNING) {
            return;
        }
        State state = cancellationRequested.remove(id)
                ? State.CANCELLED
                : exitCode == 0 ? State.SUCCEEDED : State.FAILED;
        Execution finished = execution.finished(state, exitCode);
        executions.put(id, finished);
        prune(finished.workspaceId());
        log.with("taskId", finished.taskId()).with("state", state).info("Task finished");
        events.publish(new TaskEvents.TaskFinished(finished.workspaceId(), id, finished.taskId(),
                state.name(), exitCode));
    }

    private void prune(WorkspaceId workspace) {
        List<Execution> finished = executions.values().stream()
                .filter(value -> value.workspaceId().equals(workspace) && value.state() != State.RUNNING)
                .sorted(Comparator.comparing(Execution::startedAt).reversed())
                .toList();
        finished.stream().skip(MAX_RETAINED_EXECUTIONS_PER_WORKSPACE)
                .forEach(value -> executions.remove(value.id(), value));
    }

    private static String commandLine(Task task) {
        StringBuilder line = new StringBuilder(shellQuote(task.executable()));
        for (String argument : task.arguments()) {
            line.append(' ').append(shellQuote(argument));
        }
        return line.toString();
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
