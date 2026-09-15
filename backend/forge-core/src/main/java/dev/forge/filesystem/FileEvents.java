package dev.forge.filesystem;

import dev.forge.core.Ids.WorkspaceId;
import dev.forge.core.event.Event;

/**
 * Completed filesystem occurrences.
 *
 * <p>Published both for operations the IDE performed and for changes noticed on disk, so a
 * second session — or a build running in a terminal — shows up in every open explorer.
 */
public final class FileEvents {

    private FileEvents() {
    }

    public record FileCreated(WorkspaceId workspaceId, String path, boolean directory) implements Event {
        @Override
        public String type() {
            return "file.created";
        }
    }

    public record FileChanged(WorkspaceId workspaceId, String path) implements Event {
        @Override
        public String type() {
            return "file.changed";
        }
    }

    public record FileDeleted(WorkspaceId workspaceId, String path) implements Event {
        @Override
        public String type() {
            return "file.deleted";
        }
    }

    public record FileMoved(WorkspaceId workspaceId, String from, String to) implements Event {
        @Override
        public String type() {
            return "file.moved";
        }
    }

    public record FileSaved(WorkspaceId workspaceId, String path, long size) implements Event {
        @Override
        public String type() {
            return "file.saved";
        }
    }
}
