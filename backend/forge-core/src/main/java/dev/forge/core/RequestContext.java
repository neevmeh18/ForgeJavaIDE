package dev.forge.core;

import dev.forge.core.Ids.SessionId;
import dev.forge.core.Ids.UserId;
import dev.forge.core.Ids.WorkspaceId;
import java.util.Optional;

/**
 * Who is asking, on behalf of which workspace, and how to cancel.
 *
 * <p>Passed explicitly into every command and query. There is deliberately no
 * {@code CurrentUser.get()} or {@code CurrentWorkspace.instance()}: a workspace may be driven
 * by several sessions at once (collaboration) and by non-human callers (CLI, automation, a
 * future agent), so ambient state would be wrong as soon as the framework grows up.
 *
 * <p>{@code userId} and {@code sessionId} are absent only for the handful of commands that run
 * before authentication, such as {@code auth.login}.
 */
public record RequestContext(
        UserId userId,
        SessionId sessionId,
        WorkspaceId workspaceId,
        Origin origin,
        Cancellation.Token cancellation) {

    /** Where the call came from. Useful for auditing and for future policy decisions. */
    public enum Origin {
        UI,
        EXTENSION,
        CLI,
        AUTOMATION,
        SYSTEM
    }

    public RequestContext {
        origin = origin == null ? Origin.UI : origin;
        cancellation = cancellation == null ? Cancellation.none() : cancellation;
    }

    /** Context for framework-internal work that has no user behind it. */
    public static RequestContext system() {
        return new RequestContext(null, null, null, Origin.SYSTEM, Cancellation.none());
    }

    public static RequestContext anonymous(Cancellation.Token cancellation) {
        return new RequestContext(null, null, null, Origin.UI, cancellation);
    }

    public boolean isAuthenticated() {
        return userId != null && sessionId != null;
    }

    public UserId requireUser() {
        if (userId == null) {
            throw ForgeException.unauthorized("Authentication required");
        }
        return userId;
    }

    public WorkspaceId requireWorkspace() {
        if (workspaceId == null) {
            throw ForgeException.invalidArgument("No workspace in context");
        }
        return workspaceId;
    }

    public Optional<WorkspaceId> workspace() {
        return Optional.ofNullable(workspaceId);
    }

    public RequestContext withWorkspace(WorkspaceId workspace) {
        return new RequestContext(userId, sessionId, workspace, origin, cancellation);
    }

    public RequestContext withOrigin(Origin newOrigin) {
        return new RequestContext(userId, sessionId, workspaceId, newOrigin, cancellation);
    }
}
