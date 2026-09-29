package dev.forge.scm;

import dev.forge.core.WorkspaceId;
import dev.forge.core.event.Event;

public record Committed(WorkspaceId workspaceId, String commitId, String message) implements Event {
    @Override
    public String type() {
        return "scm.committed";
    }
}
