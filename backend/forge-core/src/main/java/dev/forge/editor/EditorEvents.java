package dev.forge.editor;

import dev.forge.core.Ids.DocumentId;
import dev.forge.core.Ids.SessionId;
import dev.forge.core.Ids.WorkspaceId;
import dev.forge.core.event.Event;

/** Completed editor occurrences. */
public final class EditorEvents {

    private EditorEvents() {
    }

    public record EditorOpened(WorkspaceId workspaceId, SessionId sessionId, DocumentId documentId, String path)
            implements Event {
        @Override
        public String type() {
            return "editor.opened";
        }
    }

    public record EditorClosed(WorkspaceId workspaceId, SessionId sessionId, DocumentId documentId,
                               String path, String languageId) implements Event {
        @Override
        public String type() {
            return "editor.closed";
        }
    }

    /**
     * The shared buffer moved on. Broadcast to the whole workspace rather than one session,
     * because every session showing this document needs to know.
     */
    public record DocumentChanged(WorkspaceId workspaceId, DocumentId documentId, String path, int version)
            implements Event {
        @Override
        public String type() {
            return "editor.documentChanged";
        }
    }

    public record DirtyStateChanged(WorkspaceId workspaceId, DocumentId documentId, boolean dirty)
            implements Event {
        @Override
        public String type() {
            return "editor.dirtyStateChanged";
        }
    }
}
