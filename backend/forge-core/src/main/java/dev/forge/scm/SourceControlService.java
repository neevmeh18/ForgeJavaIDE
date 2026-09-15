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

/**
 * Source-control coordination.
 *
 * <p>Thin by design: it picks the provider that claims the workspace, enforces the couple of
 * rules that belong to the IDE rather than to any VCS (a commit needs a message; a checkout
 * with unresolved conflicts is refused), and turns results into events other features and
 * sessions can react to.
 */
public final class SourceControlService implements Lifecycle.Component {

    private static final Log log = Log.of(SourceControlService.class);

    private final List<SourceControlProvider> providers;
    private final EventBus events;

    public SourceControlService(List<SourceControlProvider> providers, EventBus events) {
        this.providers = List.copyOf(providers);
        this.events = events;
    }

    @Override
    public void start() {
        log.with("providers", providers.size()).info("Source control ready");
    }

    public RepositoryStatus status(WorkspaceId workspace) {
        return find(workspace).map(provider -> provider.status(workspace)).orElse(RepositoryStatus.NONE);
    }

    public void stage(WorkspaceId workspace, List<String> paths) {
        require(workspace).stage(workspace, paths);
        announce(workspace);
    }

    public void unstage(WorkspaceId workspace, List<String> paths) {
        require(workspace).unstage(workspace, paths);
        announce(workspace);
    }

    public void discard(WorkspaceId workspace, List<String> paths) {
        if (paths.isEmpty()) {
            throw ForgeException.invalidArgument("Nothing selected to discard");
        }
        require(workspace).discard(workspace, paths);
        announce(workspace);
    }

    public Commit commit(WorkspaceId workspace, String message, boolean amend) {
        if (message == null || message.isBlank()) {
            throw ForgeException.invalidArgument("A commit needs a message");
        }
        SourceControlProvider provider = require(workspace);
        RepositoryStatus before = provider.status(workspace);
        if (before.conflicted()) {
            throw ForgeException.conflict("Resolve conflicts before committing");
        }
        Commit commit = provider.commit(workspace, message.strip(), amend);
        log.with("workspaceId", workspace).with("commit", commit.shortId()).info("Committed");
        events.publish(new ScmEvents.Committed(workspace, commit.id(), commit.message()));
        announce(workspace);
        return commit;
    }

    public List<Branch> branches(WorkspaceId workspace) {
        return require(workspace).branches(workspace);
    }

    public void checkout(WorkspaceId workspace, String branch, boolean create) {
        SourceControlProvider provider = require(workspace);
        provider.checkout(workspace, branch, create);
        log.with("workspaceId", workspace).with("branch", branch).info("Checked out");
        events.publish(new ScmEvents.BranchChanged(workspace, branch));
        announce(workspace);
    }

    public Diff diff(WorkspaceId workspace, String path, boolean staged) {
        return require(workspace).diff(workspace, path, staged);
    }

    public List<Commit> history(WorkspaceId workspace, String path, int limit) {
        return require(workspace).history(workspace, path, Math.clamp(limit, 1, 500));
    }

    public void fetch(WorkspaceId workspace) {
        require(workspace).fetch(workspace);
        announce(workspace);
    }

    public void pull(WorkspaceId workspace) {
        require(workspace).pull(workspace);
        announce(workspace);
    }

    public void push(WorkspaceId workspace) {
        require(workspace).push(workspace);
        announce(workspace);
    }

    private void announce(WorkspaceId workspace) {
        RepositoryStatus status = status(workspace);
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
