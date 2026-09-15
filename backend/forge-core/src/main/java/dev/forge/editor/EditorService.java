package dev.forge.editor;

import dev.forge.core.ForgeException;
import dev.forge.core.Ids;
import dev.forge.core.Ids.DocumentId;
import dev.forge.core.Ids.SessionId;
import dev.forge.core.Ids.WorkspaceId;
import dev.forge.core.Lifecycle;
import dev.forge.core.event.EventBus;
import dev.forge.filesystem.FileEvents;
import dev.forge.filesystem.FileService;
import dev.forge.filesystem.Resource;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Open documents and their shared buffers.
 *
 * <p>The backend is authoritative for document identity, contents and dirty state; the frontend
 * owns how documents are arranged on screen — editor groups, splits, tab order — because that
 * is presentation and differs per client. Monaco never appears here, and nothing in this file
 * knows a browser exists.
 *
 * <p>Buffers are shared per (workspace, path). A client pushes its working copy with
 * {@code editor.update}; language tooling and, later, collaborators read the same text.
 */
public final class EditorService implements Lifecycle.Component {

    /** What {@code editor.open} returns: metadata plus the current buffer. */
    public record OpenDocument(Document document, String text) {
    }

    private static final class Buffer {
        volatile Document document;
        volatile String text;
        final java.util.Set<SessionId> viewers = ConcurrentHashMap.newKeySet();

        Buffer(Document document, String text) {
            this.document = document;
            this.text = text;
        }
    }

    private final Map<String, Buffer> buffers = new ConcurrentHashMap<>();
    private final Map<DocumentId, String> keysById = new ConcurrentHashMap<>();
    private final FileService files;
    private final EventBus events;
    private final Lifecycle.Store subscriptions = new Lifecycle.Store();
    private final List<Consumer<Document>> changeListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    public EditorService(FileService files, EventBus events) {
        this.files = files;
        this.events = events;
    }

    @Override
    public void start() {
        // Saves and external deletions both originate in the filesystem feature; the editor
        // reacts to them rather than the filesystem knowing that editors exist.
        subscriptions.add(events.subscribe(FileEvents.FileSaved.class,
                event -> find(event.workspaceId(), event.path()).ifPresent(this::markClean)));
        subscriptions.add(events.subscribe(FileEvents.FileDeleted.class,
                event -> find(event.workspaceId(), event.path()).ifPresent(buffer -> closeAll(buffer.document))));
    }

    @Override
    public void dispose() {
        subscriptions.dispose();
        buffers.clear();
        keysById.clear();
    }

    /** Opens (or joins) the document for a resource and registers the session as a viewer. */
    public OpenDocument open(Resource resource, SessionId session) {
        String key = key(resource.workspace(), resource.path());
        Buffer buffer = buffers.computeIfAbsent(key, ignored -> {
            FileService.FileContent content = files.readText(resource);
            Document document = new Document(DocumentId.of(Ids.random("doc")), resource.workspace(),
                    resource.path(), Document.languageFor(resource.path()), 1, false);
            keysById.put(document.id(), key);
            return new Buffer(document, content.text());
        });
        if (session != null) {
            buffer.viewers.add(session);
        }
        events.publish(new EditorEvents.EditorOpened(resource.workspace(), session,
                buffer.document.id(), resource.path()));
        return new OpenDocument(buffer.document, buffer.text);
    }

    /**
     * Replaces the shared buffer. Full-text updates keep the first milestone simple and
     * correct; the version check is what a future incremental protocol would build on.
     */
    public Document update(DocumentId id, String text, int expectedVersion) {
        Buffer buffer = require(id);
        if (expectedVersion > 0 && expectedVersion != buffer.document.version()) {
            throw ForgeException.conflict("Document has moved on; reopen before editing")
                    .with("documentId", id.value())
                    .with("version", String.valueOf(buffer.document.version()));
        }
        buffer.text = text;
        boolean wasDirty = buffer.document.dirty();
        buffer.document = buffer.document.changed(buffer.document.version() + 1);
        Document document = buffer.document;
        events.publish(new EditorEvents.DocumentChanged(document.workspaceId(), document.id(),
                document.path(), document.version()));
        if (!wasDirty) {
            events.publish(new EditorEvents.DirtyStateChanged(document.workspaceId(), document.id(), true));
        }
        changeListeners.forEach(listener -> listener.accept(document));
        return document;
    }

    /** Lets language tooling follow buffer changes without the editor depending on it. */
    public void onDocumentChanged(Consumer<Document> listener) {
        changeListeners.add(listener);
    }

    public String text(DocumentId id) {
        return require(id).text;
    }

    public Document document(DocumentId id) {
        return require(id).document;
    }

    /** Documents a session currently has open. */
    public List<Document> documentsFor(WorkspaceId workspace, SessionId session) {
        return buffers.values().stream()
                .filter(buffer -> buffer.document.workspaceId().equals(workspace))
                .filter(buffer -> session == null || buffer.viewers.contains(session))
                .map(buffer -> buffer.document)
                .sorted(java.util.Comparator.comparing(Document::path))
                .toList();
    }

    /**
     * Removes one session's view. The buffer survives while other sessions hold it open, and
     * a dirty buffer is kept even with no viewers so unsaved work is not silently discarded.
     */
    public void close(DocumentId id, SessionId session) {
        Buffer buffer = require(id);
        if (session != null) {
            buffer.viewers.remove(session);
        }
        events.publish(new EditorEvents.EditorClosed(buffer.document.workspaceId(), session, id,
                buffer.document.path(), buffer.document.languageId()));
        if (buffer.viewers.isEmpty() && !buffer.document.dirty()) {
            discard(buffer.document);
        }
    }

    private void markClean(Buffer buffer) {
        if (buffer.document.dirty()) {
            buffer.document = buffer.document.saved();
            events.publish(new EditorEvents.DirtyStateChanged(
                    buffer.document.workspaceId(), buffer.document.id(), false));
        }
    }

    private void closeAll(Document document) {
        events.publish(new EditorEvents.EditorClosed(document.workspaceId(), null, document.id(),
                document.path(), document.languageId()));
        discard(document);
    }

    private void discard(Document document) {
        String key = keysById.remove(document.id());
        if (key != null) {
            buffers.remove(key);
        }
    }

    private Optional<Buffer> find(WorkspaceId workspace, String path) {
        return Optional.ofNullable(buffers.get(key(workspace, path)));
    }

    private Buffer require(DocumentId id) {
        String key = keysById.get(id);
        Buffer buffer = key == null ? null : buffers.get(key);
        if (buffer == null) {
            throw ForgeException.notFound("Document is not open: " + id).with("documentId", id.value());
        }
        return buffer;
    }

    /** Buffer key. The separator is a character that cannot occur in a validated path. */
    private static String key(WorkspaceId workspace, String path) {
        return workspace.value() + '\n' + path;
    }
}
