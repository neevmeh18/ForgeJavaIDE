package dev.forge.workspace;

import dev.forge.core.ForgeException;
import dev.forge.core.Ids.SessionId;
import dev.forge.core.Ids.WorkspaceId;
import dev.forge.core.Lifecycle;
import dev.forge.core.Log;
import dev.forge.core.event.EventBus;
import dev.forge.filesystem.FileSystem;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Workspace lifecycle and the registry of what is currently open.
 *
 * <p>Implements {@link FileSystem.Locator}, which is how every other feature reaches storage:
 * they hold a {@code Locator}, not a {@code WorkspaceService}, so the filesystem, editor,
 * search and language features have no compile-time dependency on this package.
 *
 * <p>Sessions <em>attach</em> to an open workspace rather than owning it. A workspace stays
 * open while at least one session is attached, and closing is an explicit act — the groundwork
 * for several people (or a person plus an automation client) sharing one environment.
 */
public final class WorkspaceService implements FileSystem.Locator, Lifecycle.Component {

    private static final Log log = Log.of(WorkspaceService.class);

    private static final class Open {
        volatile Workspace workspace;
        final Set<SessionId> sessions = ConcurrentHashMap.newKeySet();
        final Lifecycle.Store disposables = new Lifecycle.Store();

        Open(Workspace workspace) {
            this.workspace = workspace;
        }
    }

    private final Map<String, WorkspaceProvider> providers = new LinkedHashMap<>();
    private final Map<WorkspaceId, Open> open = new ConcurrentHashMap<>();
    private final Object openLock = new Object();
    private final EventBus events;
    private final int maxOpenWorkspaces;

    public WorkspaceService(EventBus events, List<WorkspaceProvider> providers, int maxOpenWorkspaces) {
        this.events = events;
        this.maxOpenWorkspaces = maxOpenWorkspaces;
        for (WorkspaceProvider provider : providers) {
            this.providers.put(provider.scheme(), provider);
        }
        if (this.providers.isEmpty()) {
            throw new IllegalArgumentException("At least one workspace provider is required");
        }
    }

    @Override
    public void start() {
        log.with("providers", String.join(",", providers.keySet())).info("Workspace service ready");
    }

    @Override
    public void dispose() {
        List.copyOf(open.keySet()).forEach(this::close);
    }

    /** Everything any provider can offer, opened or not. */
    public List<Workspace> available() {
        List<Workspace> all = new ArrayList<>();
        for (WorkspaceProvider provider : providers.values()) {
            try {
                all.addAll(provider.discover());
            } catch (RuntimeException e) {
                log.with("scheme", provider.scheme()).warn("Workspace discovery failed", e);
            }
        }
        all.replaceAll(workspace -> open.containsKey(workspace.id()) ? open.get(workspace.id()).workspace : workspace);
        all.sort(Comparator.comparing(Workspace::name));
        return all;
    }

    public List<Workspace> opened() {
        return open.values().stream().map(entry -> entry.workspace)
                .sorted(Comparator.comparing(Workspace::name)).toList();
    }

    public Workspace create(String name, String scheme, Map<String, String> options) {
        return provider(scheme).create(name, options);
    }

    /**
     * Opens a workspace and attaches the calling session. Opening an already-open workspace is
     * not an error — it is the normal path for the second session joining.
     *
     * <p>The event is published only once the workspace is registered and visible. Listeners
     * legitimately call straight back in — the filesystem feature starts watching the workspace
     * the moment it opens — and announcing a workspace that cannot yet be looked up would make
     * that impossible.
     */
    public Workspace open(WorkspaceId id, SessionId session) {
        synchronized (openLock) {
            Open entry = open.get(id);
            if (entry == null) {
                if (open.size() >= maxOpenWorkspaces) {
                    throw ForgeException.unavailable("Too many workspaces are already open");
                }
                entry = new Open(openWith(id));
                open.put(id, entry);
                log.with("workspaceId", id).with("scheme", entry.workspace.location().scheme())
                        .info("Workspace opened");
                events.publish(new WorkspaceEvents.WorkspaceOpened(id, entry.workspace.name()));
            }
            if (session != null && entry.sessions.add(session)) {
                events.publish(new WorkspaceEvents.SessionAttached(id, session));
            }
            return entry.workspace;
        }
    }

    private Workspace openWith(WorkspaceId id) {
        Workspace resolved = locate(id);
        WorkspaceProvider provider = provider(resolved.location().scheme());
        try {
            return provider.open(id).withState(Workspace.State.OPEN);
        } catch (RuntimeException e) {
            log.with("workspaceId", id).error("Workspace open failed", e);
            throw ForgeException.normalize(e).with("workspaceId", id.value());
        }
    }

    public void attach(WorkspaceId id, SessionId session) {
        synchronized (openLock) {
            Open entry = require(id);
            if (session != null && entry.sessions.add(session)) {
                events.publish(new WorkspaceEvents.SessionAttached(id, session));
            }
        }
    }

    /** Detaches one session. The workspace stays open for whoever else is still attached. */
    public void detach(WorkspaceId id, SessionId session) {
        synchronized (openLock) {
            Open entry = open.get(id);
            if (entry != null && session != null && entry.sessions.remove(session)) {
                events.publish(new WorkspaceEvents.SessionDetached(id, session));
            }
        }
    }

    /** Atomically detaches a session and closes the workspace only if nobody remains attached. */
    public void release(WorkspaceId id, SessionId session) {
        synchronized (openLock) {
            Open entry = open.get(id);
            if (entry == null) {
                return;
            }
            if (session != null && entry.sessions.remove(session)) {
                events.publish(new WorkspaceEvents.SessionDetached(id, session));
            }
            if (entry.sessions.isEmpty()) {
                closeLocked(id, entry);
            }
        }
    }

    /** Releases an expired/logged-out session from every workspace it was attached to. */
    public void releaseSession(SessionId session) {
        if (session == null) return;
        synchronized (openLock) {
            for (var item : List.copyOf(open.entrySet())) {
                Open entry = item.getValue();
                if (entry.sessions.remove(session)) {
                    events.publish(new WorkspaceEvents.SessionDetached(item.getKey(), session));
                }
                if (entry.sessions.isEmpty()) {
                    closeLocked(item.getKey(), entry);
                }
            }
        }
    }

    /** Re-reads the workspace from its provider without disturbing attached sessions. */
    public Workspace reload(WorkspaceId id) {
        Open entry = require(id);
        entry.workspace = locate(id).withState(Workspace.State.OPEN);
        events.publish(new WorkspaceEvents.WorkspaceReloaded(id));
        return entry.workspace;
    }

    public void close(WorkspaceId id) {
        synchronized (openLock) {
            Open entry = open.get(id);
            if (entry != null) {
                closeLocked(id, entry);
            }
        }
    }

    private void closeLocked(WorkspaceId id, Open entry) {
        entry.disposables.dispose();
        try {
            provider(entry.workspace.location().scheme()).close(id);
        } catch (RuntimeException e) {
            log.with("workspaceId", id).warn("Provider close failed", e);
        }
        log.with("workspaceId", id).info("Workspace closed");
        events.publish(new WorkspaceEvents.WorkspaceClosed(id));
        open.remove(id, entry);
        entry.sessions.clear();
    }

    public boolean isOpen(WorkspaceId id) {
        return open.containsKey(id);
    }

    public Workspace require(WorkspaceId id, SessionId session) {
        Open entry = require(id);
        if (session != null && !entry.sessions.contains(session)) {
            throw ForgeException.forbidden("Session is not attached to workspace " + id)
                    .with("workspaceId", id.value());
        }
        return entry.workspace;
    }

    public Optional<Workspace> find(WorkspaceId id) {
        Open entry = open.get(id);
        return entry == null ? Optional.empty() : Optional.of(entry.workspace);
    }

    /** Sessions currently working on a workspace — the raw material for future presence. */
    public Set<SessionId> sessions(WorkspaceId id) {
        Open entry = open.get(id);
        return entry == null ? Set.of() : Set.copyOf(entry.sessions);
    }

    /** Lets a feature tie a resource (a watcher, a language server) to a workspace's lifetime. */
    public void onClose(WorkspaceId id, Lifecycle.Disposable disposable) {
        require(id).disposables.add(disposable);
    }

    @Override
    public FileSystem forWorkspace(WorkspaceId workspace) {
        Open entry = require(workspace);
        return provider(entry.workspace.location().scheme()).fileSystem(workspace);
    }

    private Workspace locate(WorkspaceId id) {
        for (WorkspaceProvider provider : providers.values()) {
            Optional<Workspace> found = provider.find(id);
            if (found.isPresent()) {
                return found.get();
            }
        }
        throw ForgeException.notFound("Unknown workspace: " + id).with("workspaceId", id.value());
    }

    private Open require(WorkspaceId id) {
        Open entry = open.get(id);
        if (entry == null) {
            throw ForgeException.notFound("Workspace is not open: " + id).with("workspaceId", id.value());
        }
        return entry;
    }

    private WorkspaceProvider provider(String scheme) {
        WorkspaceProvider provider = providers.get(scheme);
        if (provider == null) {
            throw ForgeException.unsupported("No workspace provider for scheme '" + scheme + "'");
        }
        return provider;
    }
}
