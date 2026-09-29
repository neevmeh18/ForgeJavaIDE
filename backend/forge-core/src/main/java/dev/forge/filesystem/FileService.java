package dev.forge.filesystem;

import dev.forge.core.ForgeException;
import dev.forge.core.WorkspaceId;
import dev.forge.core.Disposable;
import dev.forge.core.Log;
import dev.forge.core.event.EventBus;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;












public final class FileService {

    private static final Log log = Log.of(FileService.class);






    private final dev.forge.filesystem.Locator locator;
    private final EventBus events;
    private final long maxFileBytes;
    private final Object writeLock = new Object();

    public FileService(dev.forge.filesystem.Locator locator, EventBus events, long maxFileBytes) {
        this.locator = locator;
        this.events = events;
        this.maxFileBytes = maxFileBytes;
    }

    public List<dev.forge.filesystem.Entry> list(Resource resource) {
        return fs(resource).list(resource.path());
    }

    public dev.forge.filesystem.Stat stat(Resource resource) {
        return fs(resource).stat(resource.path());
    }

    public boolean exists(Resource resource) {
        return fs(resource).exists(resource.path());
    }





    public FileContent readText(Resource resource) {
        FileSystem fs = fs(resource);
        dev.forge.filesystem.Stat stat = fs.stat(resource.path());
        if (stat.directory()) {
            throw ForgeException.invalidArgument("Not a file: " + resource.path());
        }
        byte[] bytes = fs.read(resource.path(), maxFileBytes);
        return new FileContent(resource.path(), decodeText(bytes, resource), stat.size(),
                stat.modifiedAt(), stat.readOnly(), revision(bytes));
    }


    public SaveResult writeText(Resource resource, String text) {
        return writeText(resource, text, 0);
    }






    public SaveResult writeText(Resource resource, String text, long expectedModifiedAt) {
        return writeText(resource, text, expectedModifiedAt, null);
    }

    public SaveResult writeText(Resource resource, String text, long expectedModifiedAt, String expectedRevision) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxFileBytes) {
            throw ForgeException.invalidArgument("File exceeds the maximum writable size");
        }
        synchronized (writeLock) {
            FileSystem fs = fs(resource);
            boolean existed = fs.exists(resource.path());
            if (expectedModifiedAt > 0 && !existed) {
                throw ForgeException.conflict("File was deleted; reopen before saving");
            }
            if (existed && expectedModifiedAt > 0) {
                dev.forge.filesystem.Stat before = fs.stat(resource.path());
                if (before.modifiedAt() != expectedModifiedAt) {
                    throw ForgeException.conflict("File changed on disk; reload before saving")
                            .with("path", resource.path());
                }
            }
            if (expectedRevision != null && (!existed || !expectedRevision.equals(revision(fs.read(resource.path(), maxFileBytes)))))
                throw ForgeException.conflict("File content changed on disk; reload before saving");
            fs.write(resource.path(), bytes);
            dev.forge.filesystem.Stat stat = fs.stat(resource.path());
            if (!existed) {
                events.publish(new dev.forge.filesystem.FileCreated(resource.workspace(), resource.path(), false));
            }
            events.publish(new dev.forge.filesystem.FileSaved(resource.workspace(), resource.path(), stat.size()));
            log.with("workspaceId", resource.workspace()).with("path", resource.path()).debug("File saved");
            return new SaveResult(resource.path(), stat.size(), stat.modifiedAt());
        }
    }

    public dev.forge.filesystem.Stat createFile(Resource resource) {
        FileSystem fs = fs(resource);
        synchronized (writeLock) { fs.createFile(resource.path()); }
        events.publish(new dev.forge.filesystem.FileCreated(resource.workspace(), resource.path(), false));
        return fs.stat(resource.path());
    }

    public dev.forge.filesystem.Stat createDirectory(Resource resource) {
        FileSystem fs = fs(resource);
        synchronized (writeLock) { fs.createDirectory(resource.path()); }
        events.publish(new dev.forge.filesystem.FileCreated(resource.workspace(), resource.path(), true));
        return fs.stat(resource.path());
    }

    public void delete(Resource resource, boolean recursive) {
        if (resource.isRoot()) {
            throw ForgeException.forbidden("The workspace root cannot be deleted");
        }
        synchronized (writeLock) { fs(resource).delete(resource.path(), recursive); }
        events.publish(new dev.forge.filesystem.FileDeleted(resource.workspace(), resource.path()));
    }

    public dev.forge.filesystem.Stat move(Resource from, Resource to) {
        if (!from.workspace().equals(to.workspace())) {
            throw ForgeException.unsupported("Moving between workspaces is not supported");
        }
        if (from.isRoot()) {
            throw ForgeException.forbidden("The workspace root cannot be moved");
        }
        FileSystem fs = fs(from);
        synchronized (writeLock) { fs.move(from.path(), to.path()); }
        events.publish(new dev.forge.filesystem.FileMoved(from.workspace(), from.path(), to.path()));
        return fs.stat(to.path());
    }

    public dev.forge.filesystem.Stat copy(Resource from, Resource to) {
        if (!from.workspace().equals(to.workspace())) {
            throw ForgeException.unsupported("Copying between workspaces is not supported");
        }
        FileSystem fs = fs(from);
        synchronized (writeLock) { fs.copy(from.path(), to.path()); }
        events.publish(new dev.forge.filesystem.FileCreated(to.workspace(), to.path(), fs.stat(to.path()).directory()));
        return fs.stat(to.path());
    }





    public Disposable watch(WorkspaceId workspace) {
        FileSystem fs = locator.forWorkspace(workspace);
        return fs.watch("", true, change -> events.publish(switch (change.kind()) {
            case CREATED -> new dev.forge.filesystem.FileCreated(workspace, change.path(), change.directory());
            case CHANGED -> new dev.forge.filesystem.FileChanged(workspace, change.path());
            case DELETED -> new dev.forge.filesystem.FileDeleted(workspace, change.path());
        }));
    }

    public static String revision(byte[] bytes) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
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
