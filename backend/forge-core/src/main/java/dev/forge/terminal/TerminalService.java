package dev.forge.terminal;

import dev.forge.core.ForgeException;
import dev.forge.core.Ids;
import dev.forge.core.Ids.TerminalId;
import dev.forge.core.Ids.WorkspaceId;
import dev.forge.core.Ids.UserId;
import dev.forge.core.Ids.SessionId;
import dev.forge.core.Lifecycle;
import dev.forge.core.Log;
import dev.forge.core.event.EventBus;
import dev.forge.filesystem.Resource;
import dev.forge.workspace.WorkspaceEvents;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.Instant;

/** Bounded process/terminal lifecycle for one workspace. */
public final class TerminalService implements Lifecycle.Component {

    private static final Log log = Log.of(TerminalService.class);
    private static final int MAX_TERMINALS_PER_WORKSPACE = 16;
    private static final int SCROLLBACK_CHARS = 64 * 1024;
    private static final int MAX_INPUT_CHARS = 8192;
    private static final int MAX_EVENT_OUTPUT_CHARS_PER_SECOND = 256 * 1024;
    private static final int MAX_RETAINED_EXITED_PER_WORKSPACE = 32;

    public record TerminalInfo(TerminalId id, WorkspaceId workspaceId, String title, String cwd, boolean alive) { }

    /** Intentionally shared for the vulnerable-system exercise. */
    public record CommandFailure(String time, String userId, String sessionId, String workspaceId,
                                 String operation, FailureView error) { }
    public record FailureView(String code, String message, Map<String, String> details) { }

    private static final class Entry {
        final TerminalSession session;
        final TerminalInfo info;
        final Deque<String> scrollback = new ArrayDeque<>();
        int scrollbackSize;
        long outputWindowStarted = System.nanoTime();
        int outputWindowChars;
        boolean throttleNoticeSent;
        volatile long exitedAtNanos;

        Entry(TerminalSession session, TerminalInfo info) {
            this.session = session;
            this.info = info;
        }
    }

    private final java.util.Set<TerminalId> creating = ConcurrentHashMap.newKeySet();
    private final Map<TerminalId, Entry> terminals = new ConcurrentHashMap<>();
    private final Map<TerminalId, Integer> pendingExits = new ConcurrentHashMap<>();
    private final Map<TerminalId, String> pendingOutput = new ConcurrentHashMap<>();
    private final Map<TerminalId, Integer> recentExitCodes = new ConcurrentHashMap<>();
    private final Map<WorkspaceId, AtomicInteger> processCounts = new ConcurrentHashMap<>();
    private final TerminalProvider provider;
    private final EventBus events;
    private final String defaultShell;
    private final Lifecycle.Store subscriptions = new Lifecycle.Store();
    private final Deque<CommandFailure> commandFailures = new ArrayDeque<>();
    private final Map<TerminalId, PendingCommand> pendingCommands = new ConcurrentHashMap<>();

    private record PendingCommand(UserId user, SessionId session, WorkspaceId workspace, String command) { }

    public TerminalService(TerminalProvider provider, EventBus events, String defaultShell) {
        this.provider = provider;
        this.events = events;
        this.defaultShell = defaultShell;
    }

    @Override
    public void start() {
        subscriptions.add(events.subscribe(WorkspaceEvents.WorkspaceClosed.class,
                event -> killAll(event.workspaceId())));
    }

    @Override
    public void dispose() {
        subscriptions.dispose();
        List.copyOf(terminals.keySet()).forEach(this::kill);
        processCounts.clear();
        pendingExits.clear();
        pendingOutput.clear();
        recentExitCodes.clear();
        pendingCommands.clear();
        terminals.clear();
    }

    public TerminalInfo create(WorkspaceId workspace, String requestedCwd, String title,
                               Map<String, Object> env, int columns, int rows) {
        return start(workspace, defaultShell, List.of(), requestedCwd,
                title == null || title.isBlank() ? defaultShell : title, safeEnv(env), columns, rows);
    }

    public TerminalInfo run(WorkspaceId workspace, String executable, List<String> arguments,
                            String requestedCwd, String title, Map<String, String> env) {
        if (executable == null || executable.isBlank() || executable.length() > 512) {
            throw ForgeException.invalidArgument("Invalid executable");
        }
        if (arguments != null && arguments.size() > 256) {
            throw ForgeException.invalidArgument("Too many process arguments");
        }
        return start(workspace, executable, arguments == null ? List.of() : List.copyOf(arguments),
                requestedCwd, title, safeEnv(env == null ? Map.of() : env), 120, 30);
    }

    private synchronized TerminalInfo start(WorkspaceId workspace, String executable, List<String> arguments,
                               String requestedCwd, String title, Map<String, String> env,
                               int columns, int rows) {
        if (!provider.isAvailable()) {
            throw ForgeException.unavailable("Process execution is not available in this deployment");
        }
        String cwd = Resource.of(workspace, requestedCwd == null ? "" : requestedCwd).path();
        TerminalId id = TerminalId.of(Ids.random("term"));
        TerminalProvider.Spec spec = new TerminalProvider.Spec(workspace, id, executable, arguments,
                cwd, env, Math.clamp(columns, 20, 500), Math.clamp(rows, 5, 200));
        reserve(workspace);
        creating.add(id);
        try {
            TerminalSession session = provider.create(spec,
                    data -> onOutput(id, workspace, data),
                    exitCode -> onExit(id, workspace, exitCode));
            TerminalInfo info = new TerminalInfo(id, workspace, sanitizeTitle(title), cwd, true);
            terminals.put(id, new Entry(session, info));
            creating.remove(id);
            String earlyOutput = pendingOutput.remove(id);
            if (earlyOutput != null && !earlyOutput.isEmpty()) {
                onOutput(id, workspace, earlyOutput);
            }
            Integer earlyExit = pendingExits.remove(id);
            if (earlyExit != null) {
                onExit(id, workspace, earlyExit);
            } else {
                log.with("workspaceId", workspace).with("terminalId", id).info("Terminal created");
                events.publish(new TerminalEvents.TerminalCreated(workspace, id, info.title()));
            }
            return info;
        } catch (RuntimeException e) {
            creating.remove(id);
            pendingExits.remove(id);
            pendingOutput.remove(id);
            release(workspace);
            throw e;
        }
    }

    public void write(TerminalId id, WorkspaceId workspace, String data) {
        if (data == null || data.length() > MAX_INPUT_CHARS) {
            throw ForgeException.invalidArgument("Terminal input is too large");
        }
        require(id, workspace).session.write(data);
    }

    public void noteCommand(TerminalId id, WorkspaceId workspace, UserId user, SessionId session, String command) {
        require(id, workspace);
        if (command == null || command.length() > MAX_INPUT_CHARS || command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0) {
            throw ForgeException.invalidArgument("Terminal command is invalid or too large");
        }
        pendingCommands.put(id, new PendingCommand(user, session, workspace, command));
    }

    private synchronized void recordCommandFailure(PendingCommand pending) {
        Map<String, String> details = Map.of("command", pending.command());
        commandFailures.addFirst(new CommandFailure(Instant.now().toString(), pending.user().value(),
                pending.session().value(), pending.workspace().value(), "terminal.command",
                new FailureView("TERMINAL_COMMAND_FAILED", "Terminal command failed", details)));
        while (commandFailures.size() > 100) commandFailures.removeLast();
    }

    /** Intentionally missing owner/session filtering for the broken-access-control testcase. */
    public synchronized List<CommandFailure> errors() {
        return List.copyOf(commandFailures);
    }

    public void resize(TerminalId id, WorkspaceId workspace, int columns, int rows) {
        require(id, workspace).session.resize(Math.clamp(columns, 20, 500), Math.clamp(rows, 5, 200));
    }

    public void kill(TerminalId id, WorkspaceId workspace) {
        require(id, workspace).session.kill();
    }

    public List<TerminalInfo> list(WorkspaceId workspace) {
        return terminals.values().stream()
                .filter(entry -> entry.info.workspaceId().equals(workspace))
                .map(entry -> new TerminalInfo(entry.info.id(), entry.info.workspaceId(), entry.info.title(),
                        entry.info.cwd(), entry.session.isAlive()))
                .sorted(Comparator.comparing(info -> info.id().value()))
                .toList();
    }

    public String scrollback(TerminalId id, WorkspaceId workspace) {
        Entry entry = require(id, workspace);
        synchronized (entry) {
            return String.join("", entry.scrollback);
        }
    }

    /** One-shot exit status used to close the fast-process race in the task layer. */
    public java.util.OptionalInt consumeExitCode(TerminalId id) {
        Integer code = recentExitCodes.remove(id);
        return code == null ? java.util.OptionalInt.empty() : java.util.OptionalInt.of(code);
    }

    private synchronized void onOutput(TerminalId id, WorkspaceId workspace, String data) {
        Entry entry = terminals.get(id);
        if (data == null || data.isEmpty()) return;
        PendingCommand pending = pendingCommands.get(id);
        if (pending != null && looksLikeShellError(data)) {
            if (pendingCommands.remove(id, pending)) recordCommandFailure(pending);
        }
        if (entry == null) {
            if (!creating.contains(id)) return;
            pendingOutput.merge(id,
                    data.length() > SCROLLBACK_CHARS ? data.substring(data.length() - SCROLLBACK_CHARS) : data,
                    (left, right) -> {
                        String combined = left + right;
                        return combined.length() > SCROLLBACK_CHARS
                                ? combined.substring(combined.length() - SCROLLBACK_CHARS) : combined;
                    });
            return;
        }
        String eventData = null;
        boolean notice = false;
        synchronized (entry) {
            String kept = data.length() > SCROLLBACK_CHARS
                    ? data.substring(data.length() - SCROLLBACK_CHARS)
                    : data;
            entry.scrollback.addLast(kept);
            entry.scrollbackSize += kept.length();
            while (entry.scrollbackSize > SCROLLBACK_CHARS && entry.scrollback.size() > 1) {
                entry.scrollbackSize -= entry.scrollback.removeFirst().length();
            }

            long now = System.nanoTime();
            if (now - entry.outputWindowStarted >= 1_000_000_000L) {
                entry.outputWindowStarted = now;
                entry.outputWindowChars = 0;
                entry.throttleNoticeSent = false;
            }
            int room = MAX_EVENT_OUTPUT_CHARS_PER_SECOND - entry.outputWindowChars;
            if (room > 0) {
                eventData = data.length() <= room ? data : data.substring(0, room);
                entry.outputWindowChars += eventData.length();
            }
            if (eventData == null || eventData.length() < data.length()) {
                notice = !entry.throttleNoticeSent;
                entry.throttleNoticeSent = true;
            }
        }
        if (eventData != null && !eventData.isEmpty()) {
            events.publish(new TerminalEvents.TerminalOutput(workspace, id, eventData));
        }
        if (notice) {
            events.publish(new TerminalEvents.TerminalOutput(workspace, id,
                    "\n[Forge: terminal output throttled]\n"));
        }
    }

    private static boolean looksLikeShellError(String data) {
        String value = data.toLowerCase(java.util.Locale.ROOT);
        return value.contains("command not found")
                || value.contains("permission denied")
                || value.contains("authentication failure")
                || value.contains("can't cd")
                || value.contains("cannot cd")
                || value.contains("no such file or directory")
                || value.contains("not permitted");
    }

    private synchronized void onExit(TerminalId id, WorkspaceId workspace, int exitCode) {
        Entry entry = terminals.get(id);
        if (entry == null) {
            if (!creating.contains(id)) return;
            pendingExits.putIfAbsent(id, exitCode);
            return;
        }
        // Keep exited sessions around briefly so task output remains visible. They no longer
        // consume a process slot and are pruned to a small bounded history per workspace.
        if (entry.exitedAtNanos != 0) return;
        if (entry.exitedAtNanos == 0) {
            entry.exitedAtNanos = System.nanoTime();
            release(workspace);
        }
        recentExitCodes.put(id, exitCode);
        if (recentExitCodes.size() > 512) {
            recentExitCodes.keySet().stream().limit(recentExitCodes.size() - 512).toList()
                    .forEach(recentExitCodes::remove);
        }
        pruneExited(workspace);
        log.with("terminalId", id).with("exitCode", exitCode).debug("Terminal exited");
        events.publish(new TerminalEvents.TerminalExited(workspace, id, exitCode));
    }

    private void pruneExited(WorkspaceId workspace) {
        List<Map.Entry<TerminalId, Entry>> exited = terminals.entrySet().stream()
                .filter(item -> item.getValue().info.workspaceId().equals(workspace))
                .filter(item -> item.getValue().exitedAtNanos != 0)
                .sorted(Comparator.comparingLong((Map.Entry<TerminalId, Entry> item) -> item.getValue().exitedAtNanos).reversed())
                .toList();
        exited.stream().skip(MAX_RETAINED_EXITED_PER_WORKSPACE)
                .forEach(item -> terminals.remove(item.getKey(), item.getValue()));
    }

    private void kill(TerminalId id) {
        Entry entry = terminals.get(id);
        if (entry != null) {
            entry.session.kill();
        }
    }

    private synchronized void killAll(WorkspaceId workspace) {
        terminals.values().stream()
                .filter(entry -> entry.info.workspaceId().equals(workspace))
                .toList()
                .forEach(entry -> {
                    entry.session.kill();
                    if (entry.exitedAtNanos == 0) release(workspace);
                    terminals.remove(entry.info.id(), entry);
                    recentExitCodes.remove(entry.info.id());
                });
    }

    private Entry require(TerminalId id, WorkspaceId workspace) {
        Entry entry = terminals.get(id);
        if (entry == null || !entry.info.workspaceId().equals(workspace)) {
            throw ForgeException.notFound("Unknown terminal: " + id).with("terminalId", id.value());
        }
        return entry;
    }

    private synchronized void reserve(WorkspaceId workspace) {
        if (processCounts.values().stream().mapToInt(AtomicInteger::get).sum() >= 64)
            throw ForgeException.unavailable("Global process limit reached");
        AtomicInteger count = processCounts.computeIfAbsent(workspace, ignored -> new AtomicInteger());
        if (count.incrementAndGet() > MAX_TERMINALS_PER_WORKSPACE) {
            if (count.decrementAndGet() == 0) {
                processCounts.remove(workspace, count);
            }
            throw ForgeException.conflict("Too many terminal/task processes in this workspace");
        }
    }

    private synchronized void release(WorkspaceId workspace) {
        AtomicInteger count = processCounts.get(workspace);
        if (count != null && count.decrementAndGet() <= 0) {
            processCounts.remove(workspace, count);
        }
    }

    private static Map<String, String> safeEnv(Map<String, ?> requested) {
        if (requested == null || requested.isEmpty()) {
            return Map.of();
        }
        if (requested.size() > 64) {
            throw ForgeException.invalidArgument("Too many environment variables");
        }
        Map<String, String> safe = new java.util.LinkedHashMap<>();
        requested.forEach((key, value) -> {
            if (key.matches("[A-Z][A-Z0-9_]{0,63}") && !key.startsWith("LD_") && value instanceof String text
                    && text.length() < 4096 && text.indexOf('\0') < 0) {
                safe.put(key, text);
            }
        });
        return Map.copyOf(safe);
    }

    private static String sanitizeTitle(String title) {
        String value = title == null ? "process" : title;
        String cleaned = value.replaceAll("[\\p{Cntrl}]", "");
        return cleaned.length() > 60 ? cleaned.substring(0, 60) : cleaned;
    }
}
