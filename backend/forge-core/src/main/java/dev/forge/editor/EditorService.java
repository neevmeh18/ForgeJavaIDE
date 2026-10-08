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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Open documents and their shared, versioned buffers. */
public final class EditorService implements Lifecycle.Component {

    public record OpenDocument(Document document, String text) { }

    private static final int MAX_OPEN_DOCUMENTS = 256;
    private static final long MAX_BUFFER_CHARS = 32L * 1024 * 1024;

    private static final class Buffer {
        volatile Document document;
        volatile String text;
        volatile long sourceModifiedAt;
        volatile String sourceRevision;
        volatile boolean live = true;
        final java.util.Set<SessionId> viewers = ConcurrentHashMap.newKeySet();

        Buffer(Document document, String text, long sourceModifiedAt, String sourceRevision) {
            this.document = document;
            this.text = text;
            this.sourceModifiedAt = sourceModifiedAt;
            this.sourceRevision = sourceRevision;
        }
    }

    private final Map<String, Buffer> buffers = new ConcurrentHashMap<>();
    private final Map<DocumentId, String> keysById = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong bufferChars = new java.util.concurrent.atomic.AtomicLong();
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
        subscriptions.add(events.subscribe(dev.forge.workspace.WorkspaceEvents.WorkspaceClosed.class,
                event -> closeAffected(event.workspaceId(), "")));
        subscriptions.add(events.subscribe(dev.forge.workspace.WorkspaceEvents.SessionDetached.class, event -> {
            for (Buffer buffer : affected(event.workspaceId(), "")) {
                synchronized (buffer) {
                    buffer.viewers.remove(event.sessionId());
                    if (buffer.viewers.isEmpty()) discard(buffer.document);
                }
            }
        }));
        subscriptions.add(events.subscribe(dev.forge.scm.ScmEvents.BranchChanged.class, event -> {
            for (Buffer buffer : affected(event.workspaceId(), "")) {
                if (!files.exists(Resource.of(event.workspaceId(), buffer.document.path()))) closeAll(buffer.document);
                else refreshCleanBuffers(event.workspaceId(), buffer.document.path());
            }
        }));
        subscriptions.add(events.subscribe(FileEvents.FileChanged.class,
                event -> refreshCleanBuffers(event.workspaceId(), event.path())));
        subscriptions.add(events.subscribe(FileEvents.FileSaved.class,
                event -> refreshCleanBuffers(event.workspaceId(), event.path())));
        subscriptions.add(events.subscribe(FileEvents.FileDeleted.class,
                event -> closeAffected(event.workspaceId(), event.path())));
        subscriptions.add(events.subscribe(FileEvents.FileMoved.class,
                event -> moveAffected(event.workspaceId(), event.from(), event.to())));
    }

    @Override
    public void dispose() {
        subscriptions.dispose();
        buffers.clear();
        keysById.clear();
    }

    public OpenDocument open(Resource resource, SessionId session) {
        String key = key(resource.workspace(), resource.path());
        Buffer existing = buffers.get(key);
        if (existing == null) {
            synchronized (buffers) {
                existing = buffers.get(key);
                if (existing == null) {
                    if (buffers.size() >= MAX_OPEN_DOCUMENTS) {
                        throw ForgeException.unavailable("Too many open documents");
                    }
                    FileService.FileContent content = files.readText(resource);
                    ensureBufferBudget(content.text().length(), null);
                    Document document = new Document(DocumentId.of(Ids.random("doc")), resource.workspace(),
                            resource.path(), Document.languageFor(resource.path()), 1, false);
                    existing = new Buffer(document, content.text(), content.modifiedAt(), content.revision());
                    buffers.put(key, existing);
                    keysById.put(document.id(), key);
                }
            }
        }
        if (session != null) {
            existing.viewers.add(session);
        }
        events.publish(new EditorEvents.EditorOpened(resource.workspace(), session,
                existing.document.id(), resource.path()));
        return new OpenDocument(existing.document, existing.text);
    }

    public Document update(DocumentId id, WorkspaceId workspace, SessionId session,
                           String text, int expectedVersion) {
        Buffer buffer = require(id, workspace, session);
        synchronized (buffer) {
            require(id, workspace, session);
            requireVersion(buffer, expectedVersion);
            ensureBufferBudget(text.length(), buffer);
            buffer.text = text;
            boolean wasDirty = buffer.document.dirty();
            buffer.document = buffer.document.changed(buffer.document.version() + 1);
            publishChanged(buffer, !wasDirty);
            return buffer.document;
        }
    }

    public OpenDocument save(DocumentId id, WorkspaceId workspace, SessionId session,
                             String text, int expectedVersion) {
        Buffer buffer = require(id, workspace, session);
        synchronized (buffer) {
            require(id, workspace, session);
            requireVersion(buffer, expectedVersion);
            if (!buffer.text.equals(text)) {
                boolean wasDirty = buffer.document.dirty();
                ensureBufferBudget(text.length(), buffer);
                buffer.text = text;
                buffer.document = buffer.document.changed(buffer.document.version() + 1);
                publishChanged(buffer, !wasDirty);
            }
            FileService.SaveResult saved = files.writeText(
                    Resource.of(workspace, buffer.document.path()), buffer.text, buffer.sourceModifiedAt, buffer.sourceRevision);
            buffer.sourceModifiedAt = saved.modifiedAt();
            buffer.sourceRevision = FileService.revision(buffer.text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (buffer.document.dirty()) {
                buffer.document = buffer.document.saved();
                events.publish(new EditorEvents.DirtyStateChanged(workspace, id, false));
            }
            return new OpenDocument(buffer.document, buffer.text);
        }
    }

    public void onDocumentChanged(Consumer<Document> listener) {
        changeListeners.add(listener);
    }

    public String text(DocumentId id) {
        return require(id).text;
    }

    public String text(DocumentId id, WorkspaceId workspace, SessionId session) {
        return require(id, workspace, session).text;
    }

    public Document document(DocumentId id) {
        return require(id).document;
    }

    public Document document(DocumentId id, WorkspaceId workspace, SessionId session) {
        return require(id, workspace, session).document;
    }

    public List<Document> documentsFor(WorkspaceId workspace, SessionId session) {
        return buffers.values().stream()
                .filter(buffer -> buffer.document.workspaceId().equals(workspace))
                .filter(buffer -> session == null || buffer.viewers.contains(session))
                .map(buffer -> buffer.document)
                .sorted(java.util.Comparator.comparing(Document::path))
                .toList();
    }

    public void close(DocumentId id, WorkspaceId workspace, SessionId session) {
        Buffer buffer = require(id, workspace, session);
        synchronized (buffer) {
        if (session != null) {
            buffer.viewers.remove(session);
        }
        events.publish(new EditorEvents.EditorClosed(buffer.document.workspaceId(), session, id,
                buffer.document.path(), buffer.document.languageId()));
        if (buffer.viewers.isEmpty()) {
            discard(buffer.document);
        }
        }
    }

    private void requireVersion(Buffer buffer, int expectedVersion) {
        if (!buffer.live) throw ForgeException.conflict("Document was closed; reopen before saving");
        if (expectedVersion <= 0 || expectedVersion != buffer.document.version()) {
            throw ForgeException.conflict("Document version is stale; reload before editing")
                    .with("documentId", buffer.document.id().value())
                    .with("version", String.valueOf(buffer.document.version()));
        }
    }

    private void publishChanged(Buffer buffer, boolean becameDirty) {
        Document document = buffer.document;
        events.publish(new EditorEvents.DocumentChanged(document.workspaceId(), document.id(),
                document.path(), document.version()));
        if (becameDirty) {
            events.publish(new EditorEvents.DirtyStateChanged(document.workspaceId(), document.id(), true));
        }
        changeListeners.forEach(listener -> listener.accept(document));
    }

    private void refreshCleanBuffers(WorkspaceId workspace, String path) {
        find(workspace, path).ifPresent(buffer -> {
            synchronized (buffer) {
                if (buffer.document.dirty()) {
                    return;
                }
                try {
                    FileService.FileContent content = files.readText(Resource.of(workspace, path));
                    if (content.modifiedAt() == buffer.sourceModifiedAt && content.text().equals(buffer.text)) {
                        return;
                    }
                    ensureBufferBudget(content.text().length(), buffer);
                    buffer.text = content.text();
                    buffer.sourceModifiedAt = content.modifiedAt();
                    buffer.sourceRevision = content.revision();
                    buffer.document = new Document(buffer.document.id(), workspace, path,
                            Document.languageFor(path), buffer.document.version() + 1, false);
                    publishChanged(buffer, false);
                } catch (RuntimeException ignored) {
                    // A delete/move event will dispose the buffer if the file disappeared.
                }
            }
        });
    }

    private void closeAffected(WorkspaceId workspace, String deleted) {
        for (Buffer buffer : affected(workspace, deleted)) {
            closeAll(buffer.document);
        }
    }

    private void moveAffected(WorkspaceId workspace, String from, String to) {
        for (Buffer buffer : affected(workspace, from)) {
            synchronized (buffer) {
                Document old = buffer.document;
                String suffix = old.path().equals(from) ? "" : old.path().substring(from.length());
                String newPath = to + suffix;
                String oldKey = keysById.get(old.id());
                String newKey = key(workspace, newPath);
                if (oldKey != null) {
                    buffers.remove(oldKey, buffer);
                    buffers.put(newKey, buffer);
                    keysById.put(old.id(), newKey);
                }
                buffer.document = new Document(old.id(), workspace, newPath,
                        Document.languageFor(newPath), old.version() + 1, old.dirty());
                try {
                    buffer.sourceModifiedAt = files.stat(Resource.of(workspace, newPath)).modifiedAt();
                } catch (RuntimeException ignored) { }
                publishChanged(buffer, false);
            }
        }
    }

    private List<Buffer> affected(WorkspaceId workspace, String path) {
        String prefix = path.isEmpty() ? "" : path + "/";
        List<Buffer> result = new ArrayList<>();
        for (Buffer buffer : buffers.values()) {
            String candidate = buffer.document.path();
            if (buffer.document.workspaceId().equals(workspace)
                    && (candidate.equals(path) || candidate.startsWith(prefix))) {
                result.add(buffer);
            }
        }
        return result;
    }

    private void closeAll(Document document) {
        events.publish(new EditorEvents.EditorClosed(document.workspaceId(), null, document.id(),
                document.path(), document.languageId()));
        discard(document);
    }

    private void discard(Document document) {
        String key = keysById.remove(document.id());
        if (key != null) {
            Buffer removed = buffers.remove(key);
            if (removed != null) {
                synchronized (removed) {
                    if (removed.live) { removed.live = false; bufferChars.addAndGet(-removed.text.length()); }
                }
            }
        }
    }

    private Optional<Buffer> find(WorkspaceId workspace, String path) {
        return Optional.ofNullable(buffers.get(key(workspace, path)));
    }

    private Buffer require(DocumentId id, WorkspaceId workspace, SessionId session) {
        Buffer buffer = require(id);
        if (!buffer.document.workspaceId().equals(workspace)) {
            throw ForgeException.forbidden("Document does not belong to the active workspace");
        }
        if (session != null && !buffer.viewers.contains(session)) {
            throw ForgeException.forbidden("Document is not open in this session");
        }
        return buffer;
    }

    private Buffer require(DocumentId id) {
        String key = keysById.get(id);
        Buffer buffer = key == null ? null : buffers.get(key);
        if (buffer == null) {
            throw ForgeException.notFound("Document is not open").with("documentId", id.value());
        }
        return buffer;
    }

    private void ensureBufferBudget(long replacementChars, Buffer replacing) {
        long delta = replacementChars - (replacing == null ? 0 : replacing.text.length());
        while (true) {
            long used = bufferChars.get();
            if (replacementChars > MAX_BUFFER_CHARS || used + delta > MAX_BUFFER_CHARS)
                throw ForgeException.unavailable("Open editor buffers exceed the memory budget");
            if (bufferChars.compareAndSet(used, used + delta)) return;
        }
    }

    private static String key(WorkspaceId workspace, String path) {
        return workspace.value() + '\n' + path;
    }
}
