package dev.forge.workspace;

import dev.forge.core.ForgeException;
import dev.forge.core.SessionId;
import dev.forge.core.WorkspaceId;
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












public final class WorkspaceService implements dev.forge.filesystem.Locator, dev.forge.core.Component {

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
                events.publish(new dev.forge.workspace.WorkspaceOpened(id, entry.workspace.name()));
            }
            if (session != null && entry.sessions.add(session)) {
                events.publish(new dev.forge.workspace.SessionAttached(id, session));
            }
            return entry.workspace;
        }
    }

    private Workspace openWith(WorkspaceId id) {
        Workspace resolved = locate(id);
        WorkspaceProvider provider = provider(resolved.location().scheme());
        try {
            return provider.open(id).withState(dev.forge.workspace.State.OPEN);
        } catch (RuntimeException e) {
            log.with("workspaceId", id).error("Workspace open failed", e);
            throw ForgeException.normalize(e).with("workspaceId", id.value());
        }
    }

    public void attach(WorkspaceId id, SessionId session) {
        synchronized (openLock) {
            Open entry = require(id);
            if (session != null && entry.sessions.add(session)) {
                events.publish(new dev.forge.workspace.SessionAttached(id, session));
            }
        }
    }


    public void detach(WorkspaceId id, SessionId session) {
        synchronized (openLock) {
            Open entry = open.get(id);
            if (entry != null && session != null && entry.sessions.remove(session)) {
                events.publish(new dev.forge.workspace.SessionDetached(id, session));
            }
        }
    }


    public void release(WorkspaceId id, SessionId session) {
        synchronized (openLock) {
            Open entry = open.get(id);
            if (entry == null) {
                return;
            }
            if (session != null && entry.sessions.remove(session)) {
                events.publish(new dev.forge.workspace.SessionDetached(id, session));
            }
            if (entry.sessions.isEmpty()) {
                closeLocked(id, entry);
            }
        }
    }


    public void releaseSession(SessionId session) {
        if (session == null) return;
        synchronized (openLock) {
            for (var item : List.copyOf(open.entrySet())) {
                Open entry = item.getValue();
                if (entry.sessions.remove(session)) {
                    events.publish(new dev.forge.workspace.SessionDetached(item.getKey(), session));
                }
                if (entry.sessions.isEmpty()) {
                    closeLocked(item.getKey(), entry);
                }
            }
        }
    }


    public Workspace reload(WorkspaceId id) {
        Open entry = require(id);
        entry.workspace = locate(id).withState(dev.forge.workspace.State.OPEN);
        events.publish(new dev.forge.workspace.WorkspaceReloaded(id));
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
        events.publish(new dev.forge.workspace.WorkspaceClosed(id));
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


    public Set<SessionId> sessions(WorkspaceId id) {
        Open entry = open.get(id);
        return entry == null ? Set.of() : Set.copyOf(entry.sessions);
    }


    public void onClose(WorkspaceId id, dev.forge.core.Disposable disposable) {
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
