package dev.forge.scm;

import dev.forge.core.ForgeException;
import dev.forge.core.Ids.WorkspaceId;
import dev.forge.core.Lifecycle;
import dev.forge.core.Log;
import dev.forge.core.event.EventBus;
import dev.forge.scm.ScmTypes.Branch;
import dev.forge.scm.ScmTypes.Commit;
import dev.forge.scm.ScmTypes.Diff;
import dev.forge.scm.ScmTypes.RepositoryStatus;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Source-control coordination with one serialized operation stream per workspace. */
public final class SourceControlService implements Lifecycle.Component {

    private static final Log log = Log.of(SourceControlService.class);
    private static final int MAX_COMMIT_MESSAGE_CHARS = 16 * 1024;

    private final List<SourceControlProvider> providers;
    private final EventBus events;
    private final java.util.concurrent.locks.ReentrantLock[] locks = java.util.stream.IntStream.range(0, 64)
            .mapToObj(i -> new java.util.concurrent.locks.ReentrantLock()).toArray(java.util.concurrent.locks.ReentrantLock[]::new);

    public SourceControlService(List<SourceControlProvider> providers, EventBus events) {
        this.providers = List.copyOf(providers);
        this.events = events;
    }

    @Override
    public void start() {
        log.with("providers", providers.size()).info("Source control ready");
    }

    public RepositoryStatus status(WorkspaceId workspace) {
        try (Lease ignored = acquire(workspace)) {
            return find(workspace).map(provider -> provider.status(workspace)).orElse(RepositoryStatus.NONE);
        }
    }

    public void stage(WorkspaceId workspace, List<String> paths) {
        try (Lease ignored = acquire(workspace)) {
            require(workspace).stage(workspace, paths);
            announceLocked(workspace);
        }
    }

    public void unstage(WorkspaceId workspace, List<String> paths) {
        try (Lease ignored = acquire(workspace)) {
            require(workspace).unstage(workspace, paths);
            announceLocked(workspace);
        }
    }

    public void discard(WorkspaceId workspace, List<String> paths) {
        try (Lease ignored = acquire(workspace)) {
            if (paths.isEmpty()) {
                throw ForgeException.invalidArgument("Nothing selected to discard");
            }
            require(workspace).discard(workspace, paths);
            announceLocked(workspace);
        }
    }

    public Commit commit(WorkspaceId workspace, String message, boolean amend) {
        try (Lease ignored = acquire(workspace)) {
            if (message == null || message.isBlank()) {
                throw ForgeException.invalidArgument("A commit needs a message");
            }
            if (message.length() > MAX_COMMIT_MESSAGE_CHARS || message.indexOf('\0') >= 0) {
                throw ForgeException.invalidArgument("Commit message is too large or invalid");
            }
            SourceControlProvider provider = require(workspace);
            RepositoryStatus before = provider.status(workspace);
            if (before.conflicted()) {
                throw ForgeException.conflict("Resolve conflicts before committing");
            }
            Commit commit = provider.commit(workspace, message.strip(), amend);
            log.with("workspaceId", workspace).with("commit", commit.shortId()).info("Committed");
            events.publish(new ScmEvents.Committed(workspace, commit.id(), commit.message()));
            announceLocked(workspace);
            return commit;
        }
    }

    public List<Branch> branches(WorkspaceId workspace) {
        try (Lease ignored = acquire(workspace)) {
            return require(workspace).branches(workspace);
        }
    }

    public void checkout(WorkspaceId workspace, String branch, boolean create) {
        try (Lease ignored = acquire(workspace)) {
            SourceControlProvider provider = require(workspace);
            RepositoryStatus before = provider.status(workspace);
            if (before.conflicted()) {
                throw ForgeException.conflict("Resolve conflicts before changing branches");
            }
            provider.checkout(workspace, branch, create);
            log.with("workspaceId", workspace).with("branch", branch).info("Checked out");
            events.publish(new ScmEvents.BranchChanged(workspace, branch));
            announceLocked(workspace);
        }
    }

    public Diff diff(WorkspaceId workspace, String path, boolean staged) {
        try (Lease ignored = acquire(workspace)) {
            return require(workspace).diff(workspace, path, staged);
        }
    }

    public List<Commit> history(WorkspaceId workspace, String path, int limit) {
        try (Lease ignored = acquire(workspace)) {
            return require(workspace).history(workspace, path, Math.clamp(limit, 1, 500));
        }
    }

    public void fetch(WorkspaceId workspace) {
        try (Lease ignored = acquire(workspace)) {
            require(workspace).fetch(workspace);
            announceLocked(workspace);
        }
    }

    public void pull(WorkspaceId workspace) {
        try (Lease ignored = acquire(workspace)) {
            require(workspace).pull(workspace);
            announceLocked(workspace);
        }
    }

    public void push(WorkspaceId workspace) {
        try (Lease ignored = acquire(workspace)) {
            require(workspace).push(workspace);
            announceLocked(workspace);
        }
    }

    private Lease acquire(WorkspaceId workspace) {
        var lock = locks[Math.floorMod(workspace.hashCode(), locks.length)];
        try {
            if (!lock.tryLock(100, java.util.concurrent.TimeUnit.MILLISECONDS))
                throw ForgeException.unavailable("A source-control operation is already running");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ForgeException.cancelled("Source-control operation cancelled");
        }
        return new Lease(lock);
    }

    private record Lease(java.util.concurrent.locks.ReentrantLock lock) implements AutoCloseable {
        public void close() { lock.unlock(); }
    }

    private void announceLocked(WorkspaceId workspace) {
        SourceControlProvider provider = require(workspace);
        RepositoryStatus status = provider.status(workspace);
        events.publish(new ScmEvents.RepositoryChanged(workspace, status.branch(), status.changes().size()));
    }

    private Optional<SourceControlProvider> find(WorkspaceId workspace) {
        return providers.stream().filter(provider -> {
            try {
                return provider.isRepository(workspace);
            } catch (RuntimeException e) {
                log.with("provider", provider.id()).warn("Repository probe failed", e);
                return false;
            }
        }).findFirst();
    }

    private SourceControlProvider require(WorkspaceId workspace) {
        return find(workspace).orElseThrow(() ->
                ForgeException.unavailable("This workspace is not under source control")
                        .with("workspaceId", workspace.value()));
    }
}
