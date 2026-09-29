package dev.forge.editor;

import dev.forge.core.DocumentId;
import dev.forge.core.SessionId;
import dev.forge.core.WorkspaceId;
import dev.forge.core.event.Event;

public record DocumentChanged(WorkspaceId workspaceId, DocumentId documentId, String path, int version)
        implements Event {
    @Override
    public String type() {
        return "editor.documentChanged";
    }
}
