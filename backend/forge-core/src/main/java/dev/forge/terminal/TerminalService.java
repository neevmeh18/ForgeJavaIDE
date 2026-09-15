package dev.forge.terminal;

import dev.forge.core.ForgeException;
import dev.forge.core.Ids;
import dev.forge.core.Ids.TerminalId;
import dev.forge.core.Ids.WorkspaceId;
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

/**
 * Terminal coordination: who may start one, where it runs, and what the workbench sees.
 *
 * <p>Process execution is the most privileged thing the IDE does, so the checks live here
 * rather than in a provider: the working directory is forced through {@link Resource}
 * normalisation so it cannot escape the workspace, the number of concurrent terminals is
 * capped, and environment additions are filtered to a safe key shape.
 *
 * <p>A short scrollback is retained per terminal so a reconnecting or newly joining client can
 * be shown recent output instead of an empty screen.
 */
public final class TerminalService implements Lifecycle.Component {

    private static final Log log = Log.of(TerminalService.class);
    private static final int MAX_TERMINALS_PER_WORKSPACE = 16;
    private static final int SCROLLBACK_CHARS = 64 * 1024;
    private static final int MAX_INPUT_CHARS = 8192;

    /** What the workbench lists. The handle itself never leaves the backend. */
    public record TerminalInfo(TerminalId id, WorkspaceId workspaceId, String title, String cwd, boolean alive) {
    }

    private static final class Entry {
        final TerminalSession session;
        final TerminalInfo info;
        final Deque<String> scrollback = new ArrayDeque<>();
        final AtomicInteger scrollbackSize = new AtomicInteger();

        Entry(TerminalSession session, TerminalInfo info) {
            this.session = session;
            this.info = info;
        }
    }

    private final Map<TerminalId, Entry> terminals = new ConcurrentHashMap<>();
    private final TerminalProvider provider;
    private final EventBus events;
    private final String defaultShell;
    private final Lifecycle.Store subscriptions = new Lifecycle.Store();

    public TerminalService(TerminalProvider provider, EventBus events, String defaultShell) {
        this.provider = provider;
        this.events = events;
        this.defaultShell = defaultShell;
    }

    @Override
    public void start() {
        // Terminals belong to their workspace: closing it must not leave processes running.
        subscriptions.add(events.subscribe(WorkspaceEvents.WorkspaceClosed.class,
                event -> killAll(event.workspaceId())));
    }

    @Override
    public void dispose() {
        subscriptions.dispose();
        List.copyOf(terminals.keySet()).forEach(id -> kill(id));
    }

    public TerminalInfo create(WorkspaceId workspace, String requestedCwd, String title,
                               Map<String, Object> env, int columns, int rows) {
        if (!provider.isAvailable()) {
            throw ForgeException.unavailable("Terminals are not available in this deployment");
        }
        if (list(workspace).size() >= MAX_TERMINALS_PER_WORKSPACE) {
            throw ForgeException.conflict("Too many terminals open in this workspace");
        }
        // Normalising through Resource is what guarantees the cwd stays inside the workspace.
        String cwd = Resource.of(workspace, requestedCwd == null ? "" : requestedCwd).path();
        TerminalId id = TerminalId.of(Ids.random("term"));
        TerminalProvider.Spec spec = new TerminalProvider.Spec(workspace, id, defaultShell, List.of(),
                cwd, safeEnv(env), columns, rows);

        TerminalSession session = provider.create(spec,
                data -> onOutput(id, workspace, data),
                exitCode -> onExit(id, workspace, exitCode));

        TerminalInfo info = new TerminalInfo(id, workspace,
                title == null || title.isBlank() ? defaultShell : sanitizeTitle(title), cwd, true);
        terminals.put(id, new Entry(session, info));
        log.with("workspaceId", workspace).with("terminalId", id).info("Terminal created");
        events.publish(new TerminalEvents.TerminalCreated(workspace, id, info.title()));
        return info;
    }

    /**
     * Starts one specific program instead of an interactive shell. Used by the task feature,
     * which needs a process it can observe — the terminal is only how its output is presented.
     */
    public TerminalInfo run(WorkspaceId workspace, String executable, List<String> arguments,
                            String requestedCwd, String title, Map<String, String> env) {
        if (!provider.isAvailable()) {
            throw ForgeException.unavailable("Process execution is not available in this deployment");
        }
        String cwd = Resource.of(workspace, requestedCwd == null ? "" : requestedCwd).path();
        TerminalId id = TerminalId.of(Ids.random("term"));
        TerminalProvider.Spec spec = new TerminalProvider.Spec(workspace, id, executable, arguments,
                cwd, safeEnv(env == null ? Map.<String, String>of() : env), 120, 30);

        TerminalSession session = provider.create(spec,
                data -> onOutput(id, workspace, data),
                exitCode -> onExit(id, workspace, exitCode));

        TerminalInfo info = new TerminalInfo(id, workspace, sanitizeTitle(title), cwd, true);
        terminals.put(id, new Entry(session, info));
        events.publish(new TerminalEvents.TerminalCreated(workspace, id, info.title()));
        return info;
    }

    public void write(TerminalId id, WorkspaceId workspace, String data) {
        if (data.length() > MAX_INPUT_CHARS) {
            throw ForgeException.invalidArgument("Terminal input is too large");
        }
        require(id, workspace).session.write(data);
    }

    public void resize(TerminalId id, WorkspaceId workspace, int columns, int rows) {
        require(id, workspace).session.resize(Math.clamp(columns, 20, 500), Math.clamp(rows, 5, 200));
    }

    public void kill(TerminalId id, WorkspaceId workspace) {
        require(id, workspace);
        kill(id);
    }

    public List<TerminalInfo> list(WorkspaceId workspace) {
        return terminals.values().stream()
                .filter(entry -> entry.info.workspaceId().equals(workspace))
                .map(entry -> new TerminalInfo(entry.info.id(), entry.info.workspaceId(), entry.info.title(),
                        entry.info.cwd(), entry.session.isAlive()))
                .sorted(Comparator.comparing(info -> info.id().value()))
                .toList();
    }

    /** Recent output, for a client that just connected or reloaded. */
    public String scrollback(TerminalId id, WorkspaceId workspace) {
        Entry entry = require(id, workspace);
        synchronized (entry.scrollback) {
            return String.join("", entry.scrollback);
        }
    }

    private void onOutput(TerminalId id, WorkspaceId workspace, String data) {
        Entry entry = terminals.get(id);
        if (entry != null) {
            synchronized (entry.scrollback) {
                entry.scrollback.addLast(data);
                entry.scrollbackSize.addAndGet(data.length());
                while (entry.scrollbackSize.get() > SCROLLBACK_CHARS && entry.scrollback.size() > 1) {
                    entry.scrollbackSize.addAndGet(-entry.scrollback.removeFirst().length());
                }
            }
        }
        events.publish(new TerminalEvents.TerminalOutput(workspace, id, data));
    }

    private void onExit(TerminalId id, WorkspaceId workspace, int exitCode) {
        terminals.remove(id);
        log.with("terminalId", id).with("exitCode", exitCode).debug("Terminal exited");
        events.publish(new TerminalEvents.TerminalExited(workspace, id, exitCode));
    }

    private void kill(TerminalId id) {
        Entry entry = terminals.get(id);
        if (entry != null) {
            entry.session.kill();
        }
    }

    private void killAll(WorkspaceId workspace) {
        terminals.values().stream()
                .filter(entry -> entry.info.workspaceId().equals(workspace))
                .toList()
                .forEach(entry -> entry.session.kill());
    }

    private Entry require(TerminalId id, WorkspaceId workspace) {
        Entry entry = terminals.get(id);
        if (entry == null || !entry.info.workspaceId().equals(workspace)) {
            throw ForgeException.notFound("Unknown terminal: " + id).with("terminalId", id.value());
        }
        return entry;
    }

    /**
     * Only plain {@code NAME=value} pairs get through, and only as additions. A client cannot
     * inject {@code LD_PRELOAD}-shaped names or smuggle control characters into the child's
     * environment.
     */
    private static Map<String, String> safeEnv(Map<String, ?> requested) {
        if (requested == null || requested.isEmpty()) {
            return Map.of();
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
        String cleaned = title.replaceAll("[\\p{Cntrl}]", "");
        return cleaned.length() > 60 ? cleaned.substring(0, 60) : cleaned;
    }
}
