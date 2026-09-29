package dev.forge.scm;

import dev.forge.core.WorkspaceId;
import dev.forge.core.event.Event;

public record RepositoryChanged(WorkspaceId workspaceId, String branch, int changeCount) implements Event {
    @Override
    public String type() {
        return "scm.repositoryChanged";
    }
}
