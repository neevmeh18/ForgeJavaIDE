package dev.forge.tasks;

import dev.forge.core.ForgeException;
import dev.forge.core.Ids;
import dev.forge.core.TaskExecutionId;
import dev.forge.core.TerminalId;
import dev.forge.core.WorkspaceId;
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


public final class TaskService implements dev.forge.core.Component {

    private static final Log log = Log.of(TaskService.class);
    private static final int MAX_RETAINED_EXECUTIONS_PER_WORKSPACE = 200;





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
        subscriptions.add(events.subscribe(dev.forge.workspace.WorkspaceClosed.class, event -> {
            executions.values().removeIf(value -> {
                if (!value.workspaceId().equals(event.workspaceId())) return false;
                byTerminal.remove(value.terminalId());
                cancellationRequested.remove(value.id());
                return true;
            });
        }));
        subscriptions.add(events.subscribe(dev.forge.terminal.TerminalExited.class,
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

        dev.forge.terminal.TerminalInfo terminal = task.shell()
                ? terminals.run(workspace, "/bin/sh", List.of("-c", commandLine(task)), task.cwd(),
                        task.name(), task.env())
                : terminals.run(workspace, task.executable(), task.arguments(), task.cwd(), task.name(), task.env());

        Execution execution = new Execution(TaskExecutionId.of(Ids.random("task")), task.id(), task.name(),
                workspace, terminal.id(), State.RUNNING, -1, Instant.now());
        executions.put(execution.id(), execution);
        byTerminal.put(terminal.id(), execution.id());
        prune(workspace);
        log.with("workspaceId", workspace).with("taskId", task.id()).info("Task started");
        events.publish(new dev.forge.tasks.TaskStarted(workspace, execution.id(), task.id(), task.name()));



        var early = terminals.consumeExitCode(terminal.id());
        if (early.isPresent()) {
            finishTerminal(terminal.id(), early.getAsInt());
        }
        return executions.getOrDefault(execution.id(), execution);
    }

    public void cancel(TaskExecutionId id, WorkspaceId workspace) {
        TerminalId terminalId = null;
        synchronized (executions) {
            Execution execution = require(id, workspace);
            if (execution.state() == State.RUNNING) {
                cancellationRequested.add(id);
                terminalId = execution.terminalId();
            }
        }
        if (terminalId != null) {
            terminals.kill(terminalId, workspace);
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
        Execution finished;
        State state;
        TaskExecutionId id;
        synchronized (executions) {
            id = byTerminal.remove(terminalId);
            if (id == null) {
                return;
            }
            terminals.consumeExitCode(terminalId);
            Execution execution = executions.get(id);
            if (execution == null || execution.state() != State.RUNNING) {
                cancellationRequested.remove(id);
                return;
            }
            boolean cancellation = cancellationRequested.remove(id)
                    || exitCode == 130 || exitCode == 137 || exitCode == 143;
            state = cancellation ? State.CANCELLED : exitCode == 0 ? State.SUCCEEDED : State.FAILED;
            finished = execution.finished(state, exitCode);
            executions.put(id, finished);
            prune(finished.workspaceId());
        }
        log.with("taskId", finished.taskId()).with("state", state).info("Task finished");
        events.publish(new dev.forge.tasks.TaskFinished(finished.workspaceId(), id, finished.taskId(),
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
