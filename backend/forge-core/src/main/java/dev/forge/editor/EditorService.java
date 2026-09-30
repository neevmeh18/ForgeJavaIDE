package dev.forge.editor;

import dev.forge.core.ForgeException;
import dev.forge.core.Ids;
import dev.forge.core.Ids.DocumentId;
import dev.forge.core.Ids.SessionId;
import dev.forge.core.Ids.UserId;
import dev.forge.core.Ids.WorkspaceId;
import dev.forge.core.Lifecycle;
import dev.forge.core.Log;
import dev.forge.core.event.EventBus;
import dev.forge.filesystem.FileEvents;
import dev.forge.filesystem.FileService;
import dev.forge.filesystem.Resource;
import dev.forge.settings.SettingsEvents;
import dev.forge.settings.SettingsService;
import dev.forge.workspace.WorkspaceEvents;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
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

    private static final String AUTO_SAVE_SETTING = "files.autoSave";
    private static final long AUTO_SAVE_DELAY_MILLIS = 500;
    private static final long AUTO_SAVE_RETRY_MILLIS = 500;
    private static final Log log = Log.of(EditorService.class);

    /** What {@code editor.open} returns: metadata plus the current buffer. */
    public record OpenDocument(Document document, String text) {
    }

    private static final class Buffer {
        volatile Document document;
        volatile String text;
        final java.util.Set<SessionId> viewers = ConcurrentHashMap.newKeySet();
        boolean discarded;

        Buffer(Document document, String text) {
            this.document = document;
            this.text = text;
        }
    }

    private final Map<String, Buffer> buffers = new ConcurrentHashMap<>();
    private final Map<DocumentId, String> keysById = new ConcurrentHashMap<>();
    private final FileService files;
    private final EventBus events;
    private final SettingsService settings;
    private final Lifecycle.Store subscriptions = new Lifecycle.Store();
    private final List<Consumer<Document>> changeListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final ScheduledExecutorService autosaver;
    private final Map<WorkspaceId, Set<UserId>> workspaceUsers = new ConcurrentHashMap<>();
    private final Object scheduledPassLock = new Object();
    private final Map<WorkspaceId, ScheduledFuture<?>> scheduledPasses = new HashMap<>();
    private final Map<WorkspaceId, Long> passGenerations = new HashMap<>();

    public EditorService(FileService files, EventBus events) {
        this(files, events, null);
    }

    public EditorService(FileService files, EventBus events, SettingsService settings) {
        this.files = files;
        this.events = events;
        this.settings = settings;
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "forge-editor-autosave");
            thread.setDaemon(true);
            return thread;
        };
        this.autosaver = Executors.newSingleThreadScheduledExecutor(threadFactory);
    }

    @Override
    public void start() {
        // Saves and external deletions both originate in the filesystem feature; the editor
        // reacts to them rather than the filesystem knowing that editors exist.
        subscriptions.add(events.subscribe(FileEvents.FileSaved.class,
                event -> find(event.workspaceId(), event.path()).ifPresent(this::markClean)));
        subscriptions.add(events.subscribe(FileEvents.FileDeleted.class,
                event -> find(event.workspaceId(), event.path()).ifPresent(buffer -> closeAll(buffer.document))));
        // A session may change its active workspace before the edit's delayed pass runs. Re-arm
        // the same pass for every workspace that still has dirty buffers so switching workspaces
        // cannot strand an in-memory edit.
        subscriptions.add(events.subscribe(WorkspaceEvents.SessionAttached.class,
                event -> scheduleAutosaveForDirtyBuffers()));
        subscriptions.add(events.subscribe(WorkspaceEvents.SessionDetached.class,
                event -> scheduleAutosaveForDirtyBuffers()));
        if (settings != null) {
            subscriptions.add(events.subscribe(SettingsEvents.SettingChanged.class, this::settingChanged));
        }
    }

    @Override
    public void dispose() {
        subscriptions.dispose();
        autosaver.shutdownNow();
        synchronized (scheduledPassLock) {
            scheduledPasses.values().forEach(pass -> pass.cancel(false));
            scheduledPasses.clear();
            passGenerations.clear();
        }
        buffers.clear();
        keysById.clear();
        workspaceUsers.clear();
    }

    /** Opens (or joins) the document for a resource and registers the session as a viewer. */
    public OpenDocument open(Resource resource, SessionId session) {
        return open(resource, session, null);
    }

    /** Opens a document and remembers the user for user-layer setting resolution. */
    public OpenDocument open(Resource resource, SessionId session, UserId user) {
        String key = key(resource.workspace(), resource.path());
        while (true) {
            Buffer buffer = buffers.computeIfAbsent(key, ignored -> {
                FileService.FileContent content = files.readText(resource);
                Document document = new Document(DocumentId.of(Ids.random("doc")), resource.workspace(),
                        resource.path(), Document.languageFor(resource.path()), 1, false);
                keysById.put(document.id(), key);
                return new Buffer(document, content.text());
            });
            synchronized (buffer) {
                // A clean buffer with no viewers may have been discarded between computeIfAbsent
                // and this lock. Join only a live buffer that is still indexed.
                if (buffer.discarded || buffers.get(key) != buffer) {
                    continue;
                }
                if (session != null) {
                    buffer.viewers.add(session);
                }
                rememberUser(resource.workspace(), user);
                events.publish(new EditorEvents.EditorOpened(resource.workspace(), session,
                        buffer.document.id(), resource.path()));
                return new OpenDocument(buffer.document, buffer.text);
            }
        }
    }

    /**
     * Replaces the shared buffer. Full-text updates keep the first milestone simple and
     * correct; the version check is what a future incremental protocol would build on.
     */
    public Document update(DocumentId id, String text, int expectedVersion) {
        return update(id, text, expectedVersion, null);
    }

    /** Replaces a shared buffer and associates the caller with its workspace settings. */
    public Document update(DocumentId id, String text, int expectedVersion, UserId user) {
        Buffer buffer = require(id);
        Document document;
        synchronized (buffer) {
            requireLive(id, buffer);
            if (expectedVersion > 0 && expectedVersion != buffer.document.version()) {
                throw ForgeException.conflict("Document has moved on; reopen before editing")
                        .with("documentId", id.value())
                        .with("version", String.valueOf(buffer.document.version()));
            }
            rememberUser(buffer.document.workspaceId(), user);
            buffer.text = text;
            boolean wasDirty = buffer.document.dirty();
            buffer.document = buffer.document.changed(buffer.document.version() + 1);
            document = buffer.document;
            events.publish(new EditorEvents.DocumentChanged(document.workspaceId(), document.id(),
                    document.path(), document.version()));
            if (!wasDirty) {
                events.publish(new EditorEvents.DirtyStateChanged(document.workspaceId(), document.id(), true));
            }
            changeListeners.forEach(listener -> listener.accept(document));
        }
        if (autoSaveEnabled(document.workspaceId())) {
            scheduleAutosave(document.workspaceId(), AUTO_SAVE_DELAY_MILLIS);
        }
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
        close(id, session, null);
    }

    /** Removes one session's view without discarding a dirty retained buffer. */
    public void close(DocumentId id, SessionId session, UserId user) {
        Buffer buffer = require(id);
        synchronized (buffer) {
            requireLive(id, buffer);
            rememberUser(buffer.document.workspaceId(), user);
            if (session != null) {
                buffer.viewers.remove(session);
            }
            events.publish(new EditorEvents.EditorClosed(buffer.document.workspaceId(), session, id,
                    buffer.document.path(), buffer.document.languageId()));
            if (buffer.viewers.isEmpty() && !buffer.document.dirty()) {
                discard(buffer);
            }
        }
    }

    private void markClean(Buffer buffer) {
        synchronized (buffer) {
            if (!buffer.discarded && buffer.document.dirty()) {
                buffer.document = buffer.document.saved();
                events.publish(new EditorEvents.DirtyStateChanged(
                        buffer.document.workspaceId(), buffer.document.id(), false));
            }
        }
    }

    private void closeAll(Document document) {
        find(document.workspaceId(), document.path()).ifPresent(buffer -> {
            synchronized (buffer) {
                events.publish(new EditorEvents.EditorClosed(document.workspaceId(), null, document.id(),
                        document.path(), document.languageId()));
                discard(buffer);
            }
        });
    }

    private void discard(Buffer buffer) {
        buffer.discarded = true;
        String key = keysById.remove(buffer.document.id());
        if (key != null) {
            buffers.remove(key, buffer);
        }
    }

    private void settingChanged(SettingsEvents.SettingChanged event) {
        if (!AUTO_SAVE_SETTING.equals(event.key()) || event.workspaceId() == null) {
            return;
        }
        rememberUser(event.workspaceId(), event.userId());
        if (autoSaveEnabled(event.workspaceId())) {
            // Enabling autosave also flushes drafts that were made by another session, including
            // buffers retained after their last viewer closed.
            scheduleAutosave(event.workspaceId(), 0);
        } else {
            cancelAutosave(event.workspaceId());
        }
    }

    private void autosaveWorkspace(WorkspaceId workspace) {
        if (!autoSaveEnabled(workspace)) {
            return;
        }
        boolean dirty = false;
        for (Buffer buffer : List.copyOf(buffers.values())) {
            if (!buffer.document.workspaceId().equals(workspace)) {
                continue;
            }
            saveIfDirty(buffer);
            synchronized (buffer) {
                dirty |= !buffer.discarded && buffer.document.dirty();
            }
        }
        if (dirty && autoSaveEnabled(workspace)) {
            scheduleAutosave(workspace, AUTO_SAVE_RETRY_MILLIS);
        }
    }

    private void scheduleAutosaveForDirtyBuffers() {
        Set<WorkspaceId> dirtyWorkspaces = ConcurrentHashMap.newKeySet();
        for (Buffer buffer : List.copyOf(buffers.values())) {
            synchronized (buffer) {
                if (!buffer.discarded
                        && buffers.get(key(buffer.document.workspaceId(), buffer.document.path())) == buffer
                        && buffer.document.dirty()) {
                    dirtyWorkspaces.add(buffer.document.workspaceId());
                }
            }
        }
        dirtyWorkspaces.stream()
                .filter(this::autoSaveEnabled)
                .forEach(workspace -> scheduleAutosave(workspace, AUTO_SAVE_DELAY_MILLIS));
    }

    private void saveIfDirty(Buffer buffer) {
        synchronized (buffer) {
            if (buffer.discarded
                    || buffers.get(key(buffer.document.workspaceId(), buffer.document.path())) != buffer
                    || !buffer.document.dirty()) {
                return;
            }
            try {
                // Keep the buffer lock through the write and FileSaved callback. An update or a
                // close can then only happen before or after this complete save operation.
                files.writeText(Resource.of(buffer.document.workspaceId(), buffer.document.path()), buffer.text);
            } catch (RuntimeException e) {
                log.with("workspaceId", buffer.document.workspaceId())
                        .with("path", buffer.document.path())
                        .warn("Autosave failed; the buffer remains dirty", e);
            }
        }
    }

    private void scheduleAutosave(WorkspaceId workspace, long delayMillis) {
        if (autosaver.isShutdown() || !autoSaveEnabled(workspace)) {
            return;
        }
        synchronized (scheduledPassLock) {
            if (autosaver.isShutdown()) {
                return;
            }
            ScheduledFuture<?> previous = scheduledPasses.remove(workspace);
            if (previous != null) {
                previous.cancel(false);
            }
            long generation = passGenerations.merge(workspace, 1L, Long::sum);
            ScheduledFuture<?> pass = autosaver.schedule(
                    () -> runAutosavePass(workspace, generation), delayMillis, TimeUnit.MILLISECONDS);
            scheduledPasses.put(workspace, pass);
        }
    }

    private void runAutosavePass(WorkspaceId workspace, long generation) {
        synchronized (scheduledPassLock) {
            if (!Objects.equals(passGenerations.get(workspace), generation)) {
                return;
            }
            scheduledPasses.remove(workspace);
        }
        autosaveWorkspace(workspace);
    }

    private void cancelAutosave(WorkspaceId workspace) {
        synchronized (scheduledPassLock) {
            ScheduledFuture<?> previous = scheduledPasses.remove(workspace);
            if (previous != null) {
                previous.cancel(false);
            }
            passGenerations.merge(workspace, 1L, Long::sum);
        }
    }

    private boolean autoSaveEnabled(WorkspaceId workspace) {
        if (settings == null) {
            return false;
        }
        Set<UserId> users = workspaceUsers.getOrDefault(workspace, Set.of());
        if (users.isEmpty()) {
            return settingValue(null, workspace);
        }
        // A workspace override is resolved for every user and therefore wins over their user
        // setting. Without one, any attached/remembered user's enabled setting activates the
        // workspace pass, which keeps the pass independent from one editor session.
        return users.stream().anyMatch(user -> settingValue(user, workspace));
    }

    private boolean settingValue(UserId user, WorkspaceId workspace) {
        try {
            return settings.value(AUTO_SAVE_SETTING, user, workspace, Boolean.class, false);
        } catch (RuntimeException e) {
            // Embeddings may construct the editor without the product's built-in definitions.
            // Autosave is opt-in, so an unavailable definition is safely treated as disabled.
            return false;
        }
    }

    private void rememberUser(WorkspaceId workspace, UserId user) {
        if (user != null) {
            workspaceUsers.computeIfAbsent(workspace, ignored -> ConcurrentHashMap.newKeySet()).add(user);
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

    private void requireLive(DocumentId id, Buffer buffer) {
        if (buffer.discarded || keysById.get(id) == null
                || buffers.get(keysById.get(id)) != buffer) {
            throw ForgeException.notFound("Document is not open: " + id).with("documentId", id.value());
        }
    }

    /** Buffer key. The separator is a character that cannot occur in a validated path. */
    private static String key(WorkspaceId workspace, String path) {
        return workspace.value() + '\n' + path;
    }
}
