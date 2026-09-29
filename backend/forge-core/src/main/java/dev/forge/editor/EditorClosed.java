package dev.forge.editor;

import dev.forge.core.DocumentId;
import dev.forge.core.SessionId;
import dev.forge.core.WorkspaceId;
import dev.forge.core.event.Event;

public record EditorClosed(WorkspaceId workspaceId, SessionId sessionId, DocumentId documentId,
                           String path, String languageId) implements Event {
    @Override
    public String type() {
        return "editor.closed";
    }
}
