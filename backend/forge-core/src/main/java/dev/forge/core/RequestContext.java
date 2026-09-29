package dev.forge.core;

import dev.forge.core.SessionId;
import dev.forge.core.UserId;
import dev.forge.core.WorkspaceId;
import java.util.Optional;












public record RequestContext(
        UserId userId,
        SessionId sessionId,
        WorkspaceId workspaceId,
        Origin origin,
        Cancellation.Token cancellation) {




    public RequestContext {
        origin = origin == null ? Origin.UI : origin;
        cancellation = cancellation == null ? Cancellation.none() : cancellation;
    }


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
