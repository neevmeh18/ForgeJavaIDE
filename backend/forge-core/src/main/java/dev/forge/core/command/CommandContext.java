package dev.forge.core.command;

import dev.forge.core.Args;
import dev.forge.core.Cancellation;
import dev.forge.core.Ids.SessionId;
import dev.forge.core.Ids.UserId;
import dev.forge.core.Ids.WorkspaceId;
import dev.forge.core.RequestContext;

/**
 * Everything a command handler is allowed to know: which command, which arguments, and the
 * caller's {@link RequestContext}.
 *
 * <p>Handlers receive this and nothing else. That is what lets the same handler serve a
 * keyboard shortcut, the command palette, an extension, the CLI and future automation without
 * knowing which one it is talking to.
 */
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

    /** The workspace this command acts on, failing with a structured error if there is none. */
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
