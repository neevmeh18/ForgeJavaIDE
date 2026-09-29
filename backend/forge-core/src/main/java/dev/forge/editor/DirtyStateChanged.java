package dev.forge.editor;

import dev.forge.core.DocumentId;
import dev.forge.core.SessionId;
import dev.forge.core.WorkspaceId;
import dev.forge.core.event.Event;

public record DirtyStateChanged(WorkspaceId workspaceId, DocumentId documentId, boolean dirty)
        implements Event {
    @Override
    public String type() {
        return "editor.dirtyStateChanged";
    }
}
