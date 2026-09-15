package dev.forge.filesystem;

import dev.forge.core.ForgeException;
import dev.forge.core.Ids.WorkspaceId;
import dev.forge.core.Lifecycle.Disposable;
import dev.forge.core.Log;
import dev.forge.core.event.EventBus;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Filesystem coordination for the whole IDE.
 *
 * <p>Sits between features and the {@link FileSystem} capability and owns the parts that must
 * not be re-implemented per feature: resolving which filesystem serves a workspace, enforcing
 * size limits, refusing to hand binary content to a text editor, and publishing the events other
 * features and other sessions react to.
 *
 * <p>The editor, search, source control and language tooling all go through here, so none of
 * them contains a {@code java.io.File} or an assumption that the workspace is local.
 */
public final class FileService {

    private static final Log log = Log.of(FileService.class);

    /** Text handed to the editor, with the metadata needed to detect a stale save. */
    public record FileContent(String path, String text, long size, long modifiedAt, boolean readOnly) {
    }

    public record SaveResult(String path, long size, long modifiedAt) {
    }

    private final FileSystem.Locator locator;
    private final EventBus events;
    private final long maxFileBytes;

    public FileService(FileSystem.Locator locator, EventBus events, long maxFileBytes) {
        this.locator = locator;
        this.events = events;
        this.maxFileBytes = maxFileBytes;
    }

    public List<FileSystem.Entry> list(Resource resource) {
        return fs(resource).list(resource.path());
    }

    public FileSystem.Stat stat(Resource resource) {
        return fs(resource).stat(resource.path());
    }

    public boolean exists(Resource resource) {
        return fs(resource).exists(resource.path());
    }

    /**
     * Reads a text file. Binary content is refused rather than mangled: an editor that opens a
     * PNG as UTF-8 and saves it back has destroyed the file.
     */
    public FileContent readText(Resource resource) {
        FileSystem fs = fs(resource);
        FileSystem.Stat stat = fs.stat(resource.path());
        if (stat.directory()) {
            throw ForgeException.invalidArgument("Not a file: " + resource.path());
        }
        byte[] bytes = fs.read(resource.path(), maxFileBytes);
        return new FileContent(resource.path(), decodeText(bytes, resource), stat.size(),
                stat.modifiedAt(), stat.readOnly());
    }

    /** Writes text and announces it. This is the tail of {@code file.save}. */
    public SaveResult writeText(Resource resource, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxFileBytes) {
            throw ForgeException.invalidArgument("File exceeds the maximum writable size");
        }
        FileSystem fs = fs(resource);
        boolean existed = fs.exists(resource.path());
        fs.write(resource.path(), bytes);
        FileSystem.Stat stat = fs.stat(resource.path());
        if (!existed) {
            events.publish(new FileEvents.FileCreated(resource.workspace(), resource.path(), false));
        }
        events.publish(new FileEvents.FileSaved(resource.workspace(), resource.path(), stat.size()));
        log.with("workspaceId", resource.workspace()).with("path", resource.path()).debug("File saved");
        return new SaveResult(resource.path(), stat.size(), stat.modifiedAt());
    }

    public FileSystem.Stat createFile(Resource resource) {
        FileSystem fs = fs(resource);
        fs.createFile(resource.path());
        events.publish(new FileEvents.FileCreated(resource.workspace(), resource.path(), false));
        return fs.stat(resource.path());
    }

    public FileSystem.Stat createDirectory(Resource resource) {
        FileSystem fs = fs(resource);
        fs.createDirectory(resource.path());
        events.publish(new FileEvents.FileCreated(resource.workspace(), resource.path(), true));
        return fs.stat(resource.path());
    }

    public void delete(Resource resource, boolean recursive) {
        if (resource.isRoot()) {
            throw ForgeException.forbidden("The workspace root cannot be deleted");
        }
        fs(resource).delete(resource.path(), recursive);
        events.publish(new FileEvents.FileDeleted(resource.workspace(), resource.path()));
    }

    public FileSystem.Stat move(Resource from, Resource to) {
        if (!from.workspace().equals(to.workspace())) {
            throw ForgeException.unsupported("Moving between workspaces is not supported");
        }
        if (from.isRoot()) {
            throw ForgeException.forbidden("The workspace root cannot be moved");
        }
        FileSystem fs = fs(from);
        fs.move(from.path(), to.path());
        events.publish(new FileEvents.FileMoved(from.workspace(), from.path(), to.path()));
        return fs.stat(to.path());
    }

    public FileSystem.Stat copy(Resource from, Resource to) {
        if (!from.workspace().equals(to.workspace())) {
            throw ForgeException.unsupported("Copying between workspaces is not supported");
        }
        FileSystem fs = fs(from);
        fs.copy(from.path(), to.path());
        events.publish(new FileEvents.FileCreated(to.workspace(), to.path(), fs.stat(to.path()).directory()));
        return fs.stat(to.path());
    }

    /**
     * Mirrors on-disk changes onto the event bus so every session sees edits made outside the
     * IDE — a build writing output, a git checkout, a collaborator's save.
     */
    public Disposable watch(WorkspaceId workspace) {
        FileSystem fs = locator.forWorkspace(workspace);
        return fs.watch("", true, change -> events.publish(switch (change.kind()) {
            case CREATED -> new FileEvents.FileCreated(workspace, change.path(), change.directory());
            case CHANGED -> new FileEvents.FileChanged(workspace, change.path());
            case DELETED -> new FileEvents.FileDeleted(workspace, change.path());
        }));
    }

    private FileSystem fs(Resource resource) {
        return locator.forWorkspace(resource.workspace());
    }

    private static String decodeText(byte[] bytes, Resource resource) {
        int probe = Math.min(bytes.length, 8000);
        for (int i = 0; i < probe; i++) {
            if (bytes[i] == 0) {
                throw ForgeException.unsupported("Binary file cannot be opened as text: " + resource.path());
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw ForgeException.unsupported("File is not valid UTF-8 text: " + resource.path());
        }
    }
}
