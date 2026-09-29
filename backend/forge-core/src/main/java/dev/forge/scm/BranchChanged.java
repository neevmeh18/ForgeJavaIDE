package dev.forge.scm;

import dev.forge.core.WorkspaceId;
import dev.forge.core.event.Event;

public record BranchChanged(WorkspaceId workspaceId, String branch) implements Event {
    @Override
    public String type() {
        return "scm.branchChanged";
    }
}
