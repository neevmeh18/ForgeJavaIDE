package dev.forge.core.command;

import dev.forge.core.Args;
import dev.forge.core.Cancellation;
import dev.forge.core.SessionId;
import dev.forge.core.UserId;
import dev.forge.core.WorkspaceId;
import dev.forge.core.RequestContext;









public record CommandContext(
        CommandId commandId,
        Args args,
        RequestContext request,
        String executionId) {

    public UserId userId() {
        return request.userId();
    }

    public SessionId sessionId() {
        return request.sessionId();
    }

    public WorkspaceId workspaceId() {
        return request.workspaceId();
    }


    public WorkspaceId requireWorkspace() {
        return request.requireWorkspace();
    }

    public UserId requireUser() {
        return request.requireUser();
    }

    public Cancellation.Token cancellation() {
        return request.cancellation();
    }
}
