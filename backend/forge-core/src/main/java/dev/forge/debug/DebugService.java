package dev.forge.debug;

import dev.forge.core.ForgeException;
import dev.forge.core.Ids;
import dev.forge.core.Ids.DebugSessionId;
import dev.forge.core.Ids.WorkspaceId;
import dev.forge.core.Lifecycle;
import dev.forge.core.Log;
import dev.forge.core.event.EventBus;
import dev.forge.debug.DebugTypes.Breakpoint;
import dev.forge.debug.DebugTypes.DebugConfiguration;
import dev.forge.debug.DebugTypes.SessionInfo;
import dev.forge.debug.DebugTypes.SessionState;
import dev.forge.debug.DebugTypes.StackFrame;
import dev.forge.debug.DebugTypes.ThreadInfo;
import dev.forge.debug.DebugTypes.Variable;
import dev.forge.filesystem.Resource;
import dev.forge.workspace.WorkspaceEvents;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Debug session and breakpoint coordination.
 *
 * <p>Breakpoints live here, per workspace, independently of any session: you set them before
 * starting a debuggee and they survive it ending. Sessions are routed to whichever
 * {@link DebugAdapter} handles the configuration's type — so adding support for a new runtime
 * is registering an adapter, not editing this class.
 *
 * <p>No adapter ships with the framework today; {@code debug.start} reports {@code UNSUPPORTED}
 * with the requested type until a product or extension registers one. Breakpoint management
 * works regardless, because it is workspace state rather than adapter behaviour.
 */
public final class DebugService implements Lifecycle.Component {

    private static final Log log = Log.of(DebugService.class);
    private static final int MAX_BREAKPOINTS_PER_WORKSPACE = 500;
    private static final int MAX_DEBUG_OUTPUT_CHARS = 64 * 1024;
    private static final int MAX_DEBUG_TEXT_CHARS = 4096;

    private record Active(DebugSessionId id, WorkspaceId workspace, DebugAdapter adapter, SessionInfo info) {
    }

    private final Map<String, DebugAdapter> adapters = new ConcurrentHashMap<>();
    private final Map<DebugSessionId, Active> sessions = new ConcurrentHashMap<>();
    private final Map<WorkspaceId, List<Breakpoint>> breakpoints = new ConcurrentHashMap<>();
    private final EventBus events;
    private final Lifecycle.Store subscriptions = new Lifecycle.Store();

    public DebugService(EventBus events) {
        this.events = events;
    }

    @Override
    public void start() {
        subscriptions.add(events.subscribe(WorkspaceEvents.WorkspaceClosed.class, event -> {
            breakpoints.remove(event.workspaceId());
            sessions.values().stream()
                    .filter(active -> active.workspace().equals(event.workspaceId()))
                    .toList()
                    .forEach(active -> stop(active.id(), active.workspace()));
        }));
    }

    @Override
    public void dispose() {
        subscriptions.dispose();
        List.copyOf(sessions.values()).forEach(active -> stop(active.id(), active.workspace()));
    }

    public Lifecycle.Disposable register(DebugAdapter adapter) {
        adapters.put(adapter.type(), adapter);
        log.with("adapter", adapter.type()).info("Debug adapter registered");
        return () -> adapters.remove(adapter.type(), adapter);
    }

    public List<String> adapterTypes() {
        return adapters.keySet().stream().sorted().toList();
    }

    public synchronized SessionInfo start(WorkspaceId workspace, DebugConfiguration configuration) {
        DebugAdapter adapter = adapters.get(configuration.type());
        if (adapter == null) {
            throw ForgeException.unsupported("No debug adapter for type '" + configuration.type() + "'")
                    .with("debugType", configuration.type());
        }
        if (sessions.size() >= 32) throw ForgeException.unavailable("Too many debug sessions");
        DebugSessionId id = DebugSessionId.of(Ids.random("dbg"));
        SessionInfo info = new SessionInfo(id.value(), configuration.name(), configuration.type(),
                SessionState.STARTING, List.of());
        sessions.put(id, new Active(id, workspace, adapter, info));

        try {
            adapter.start(id, workspace, configuration, listener(workspace));
            breakpointsByPath(workspace).forEach((path, forPath) -> adapter.setBreakpoints(id, path, forPath));
        } catch (RuntimeException | Error failure) {
            sessions.remove(id);
            try { adapter.stop(id); } catch (RuntimeException cleanup) { log.warn("Debug startup cleanup failed", cleanup); }
            throw failure;
        }

        log.with("workspaceId", workspace).with("debugSessionId", id).info("Debug session started");
        events.publish(new DebugEvents.DebugSessionStarted(workspace, id, configuration.name(),
                configuration.type()));
        return info;
    }

    public void stop(DebugSessionId id, WorkspaceId workspace) {
        Active active = require(id, workspace);
        if (!sessions.remove(id, active)) return;
        try {
            active.adapter().stop(id);
        } catch (RuntimeException e) {
            log.with("debugSessionId", id).warn("Debug adapter stop failed", e);
        }
        events.publish(new DebugEvents.DebugSessionEnded(workspace, id, 0));
    }

    public void resume(DebugSessionId id, WorkspaceId workspace, int threadId) {
        require(id, workspace).adapter().resume(id, threadId);
    }

    public void pause(DebugSessionId id, WorkspaceId workspace, int threadId) {
        require(id, workspace).adapter().pause(id, threadId);
    }

    public void step(DebugSessionId id, WorkspaceId workspace, String kind, int threadId) {
        DebugAdapter adapter = require(id, workspace).adapter();
        switch (kind) {
            case "over" -> adapter.stepOver(id, threadId);
            case "into" -> adapter.stepInto(id, threadId);
            case "out" -> adapter.stepOut(id, threadId);
            default -> throw ForgeException.invalidArgument("Unknown step kind: " + kind);
        }
    }

    public List<ThreadInfo> threads(DebugSessionId id, WorkspaceId workspace) {
        return require(id, workspace).adapter().threads(id);
    }

    public List<StackFrame> stackTrace(DebugSessionId id, WorkspaceId workspace, int threadId) {
        return require(id, workspace).adapter().stackTrace(id, threadId);
    }

    public List<Variable> variables(DebugSessionId id, WorkspaceId workspace, int frameId, int reference) {
        return require(id, workspace).adapter().variables(id, frameId, reference);
    }

    public String evaluate(DebugSessionId id, WorkspaceId workspace, int frameId, String expression) {
        return require(id, workspace).adapter().evaluate(id, frameId, expression);
    }

    public List<SessionInfo> sessions(WorkspaceId workspace) {
        return sessions.values().stream()
                .filter(active -> active.workspace().equals(workspace))
                .map(Active::info)
                .toList();
    }

    /** Whether any session is live — the availability condition behind {@code debug.stop}. */
    public boolean hasActiveSession(WorkspaceId workspace) {
        return sessions.values().stream().anyMatch(active -> active.workspace().equals(workspace));
    }

    public List<Breakpoint> breakpoints(WorkspaceId workspace) {
        return List.copyOf(breakpoints.getOrDefault(workspace, List.of()));
    }

    /** Toggles a breakpoint at a line, returning the workspace's full set for that file. */
    public synchronized List<Breakpoint> toggleBreakpoint(WorkspaceId workspace, String rawPath, int line, String condition) {
        if (line < 1 || line > 10_000_000 || (condition != null && condition.length() > 4096))
            throw ForgeException.invalidArgument("Invalid breakpoint");
        String path = Resource.of(workspace, rawPath).path();
        if (path.isEmpty()) throw ForgeException.invalidArgument("Breakpoint needs a file path");
        List<Breakpoint> current = new ArrayList<>(breakpoints.getOrDefault(workspace, List.of()));
        Optional<Breakpoint> existing = current.stream()
                .filter(breakpoint -> breakpoint.path().equals(path) && breakpoint.line() == line)
                .findFirst();
        if (existing.isPresent()) {
            current.remove(existing.get());
        } else {
            if (current.size() >= MAX_BREAKPOINTS_PER_WORKSPACE) {
                throw ForgeException.conflict("Too many breakpoints in this workspace");
            }
            current.add(new Breakpoint(Ids.random("bp"), path, line, true, condition, false));
        }
        current.sort(Comparator.comparing(Breakpoint::path).thenComparingInt(Breakpoint::line));
        breakpoints.put(workspace, List.copyOf(current));

        List<Breakpoint> forPath = current.stream().filter(bp -> bp.path().equals(path)).toList();
        sessions.values().stream()
                .filter(active -> active.workspace().equals(workspace))
                .forEach(active -> active.adapter().setBreakpoints(active.id(), path, forPath));
        events.publish(new DebugEvents.BreakpointsChanged(workspace, path, forPath));
        return forPath;
    }

    private Map<String, List<Breakpoint>> breakpointsByPath(WorkspaceId workspace) {
        return breakpoints.getOrDefault(workspace, List.of()).stream()
                .collect(java.util.stream.Collectors.groupingBy(Breakpoint::path));
    }

    private DebugAdapter.Listener listener(WorkspaceId workspace) {
        return new DebugAdapter.Listener() {
            @Override
            public void stopped(DebugSessionId session, int threadId, String reason) {
                transition(session, workspace, SessionState.STOPPED);
                events.publish(new DebugEvents.DebugStopped(workspace, session, threadId, bounded(reason, MAX_DEBUG_TEXT_CHARS)));
            }

            @Override
            public void continued(DebugSessionId session, int threadId) {
                transition(session, workspace, SessionState.RUNNING);
                events.publish(new DebugEvents.DebugContinued(workspace, session, threadId));
            }

            @Override
            public void output(DebugSessionId session, String category, String text) {
                events.publish(new DebugEvents.DebugOutput(workspace, session, bounded(category, 128), bounded(text, MAX_DEBUG_OUTPUT_CHARS)));
            }

            @Override
            public void terminated(DebugSessionId session, int exitCode) {
                Active active = sessions.get(session);
                if (active == null || !active.workspace().equals(workspace) || !sessions.remove(session, active)) return;
                events.publish(new DebugEvents.DebugSessionEnded(workspace, session, exitCode));
            }
        };
    }

    private void transition(DebugSessionId id, WorkspaceId workspace, SessionState state) {
        sessions.computeIfPresent(id, (key, active) -> active.workspace().equals(workspace)
                ? new Active(id, workspace, active.adapter(), new SessionInfo(id.value(), active.info().name(), active.info().type(), state, active.info().threads())) : active);
    }

    private static String bounded(String value, int max) {
        if (value == null) return "";
        String clean = value.replace('\0', '?');
        return clean.length() <= max ? clean : clean.substring(0, max) + "…[truncated]";
    }

    private Active require(DebugSessionId id, WorkspaceId workspace) {
        Active active = sessions.get(id);
        if (active == null || !active.workspace().equals(workspace)) {
            throw ForgeException.notFound("Unknown debug session: " + id);
        }
        return active;
    }
}
