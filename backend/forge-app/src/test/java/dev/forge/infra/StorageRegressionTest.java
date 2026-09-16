package dev.forge.infra;

import dev.forge.core.*;
import dev.forge.core.Ids.*;
import dev.forge.core.event.EventBus;
import dev.forge.editor.*;
import dev.forge.filesystem.*;
import dev.forge.search.SearchService;
import dev.forge.settings.*;
import dev.forge.state.StateStore;
import dev.forge.workspace.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class StorageRegressionTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final WorkspaceId W = WorkspaceId.of("one");
    private final SessionId S = SessionId.of("session");
    private LocalFileSystem fs(Path root) { return new LocalFileSystem(root, 1024 * 1024, 100, 1000); }

    @Test public void boundedFilesystemRoundTripAndContainment() throws Exception {
        Path root = temporary.newFolder().toPath(); var fs = fs(root);
        fs.write("src/Main.java", "hello".getBytes());
        assertEquals("hello", new String(fs.read("src/Main.java", 100)));
        assertEquals(1, fs.list("src").size());
        assertThrows(ForgeException.class, () -> fs.read("../unrelated", 100));
        fs.copy("src/Main.java", "src/Copy.java");
        fs.move("src/Copy.java", "src/Moved.java");
        fs.delete("src/Moved.java", false);
        assertFalse(fs.exists("src/Moved.java"));
    }

    @Test public void symlinkWorkspaceAndStateRootsAreRejected() throws Exception {
        Path base = temporary.newFolder().toPath(); Path real = Files.createDirectory(base.resolve("real"));
        Path link = Files.createSymbolicLink(base.resolve("link"), real);
        assertThrows(ForgeException.class, () -> new LocalWorkspaceProvider(link, 1024, 100, 1000));
        assertThrows(ForgeException.class, () -> fs(link));
        Path data = Files.createDirectory(base.resolve("data"));
        Files.createSymbolicLink(data.resolve("state"), real);
        assertThrows(ForgeException.class, () -> new FileStateStore(data, 1024, 512));
    }

    @Test public void staleSaveOwnershipAndDeletedFileProtection() throws Exception {
        Path root = temporary.newFolder().toPath(); var fs = fs(root); fs.write("a.txt", "first".getBytes());
        EventBus events = new EventBus(); FileService files = new FileService(w -> fs, events, 100000);
        EditorService editors = new EditorService(files, events); editors.start();
        try {
            var opened = editors.open(Resource.of(W, "a.txt"), S); var id = opened.document().id();
            var changed = editors.update(id, W, S, "second", 1);
            assertEquals(2, changed.version());
            assertThrows(ForgeException.class, () -> editors.update(id, W, S, "stale", 1));
            assertThrows(ForgeException.class, () -> editors.save(id, WorkspaceId.of("other"), S, "bad", 2));
            assertThrows(ForgeException.class, () -> editors.text(id, W, SessionId.of("other")));
            var saved = editors.save(id, W, S, "second", 2);
            assertFalse(saved.document().dirty());
            fs.write("a.txt", "external".getBytes());
            assertThrows(ForgeException.class, () -> editors.save(id, W, S, "local", saved.document().version()));
            Files.delete(root.resolve("a.txt"));
            assertThrows(ForgeException.class, () -> editors.save(id, W, S, "local", editors.document(id).version()));
            assertFalse(Files.exists(root.resolve("a.txt")));
        } finally { editors.dispose(); }
    }

    @Test public void renameDeleteAndWorkspaceDetachReconcileDocuments() throws Exception {
        var fs = fs(temporary.newFolder().toPath()); fs.write("a.txt", "first".getBytes());
        EventBus events = new EventBus(); FileService files = new FileService(w -> fs, events, 100000);
        EditorService editors = new EditorService(files, events); editors.start();
        try {
            var id = editors.open(Resource.of(W, "a.txt"), S).document().id();
            files.move(Resource.of(W, "a.txt"), Resource.of(W, "b.txt"));
            assertEquals("b.txt", editors.document(id).path());
            files.delete(Resource.of(W, "b.txt"), false);
            assertThrows(ForgeException.class, () -> editors.document(id));
            fs.write("c.txt", "third".getBytes());
            var next = editors.open(Resource.of(W, "c.txt"), S).document().id();
            editors.update(next, W, S, "dirty", 1);
            events.publish(new WorkspaceEvents.SessionDetached(W, S));
            assertTrue(editors.documentsFor(W, S).isEmpty());
        } finally { editors.dispose(); }
    }

    @Test public void watcherReportsExternalChangeAndDelete() throws Exception {
        Path root = temporary.newFolder().toPath(); var fs = fs(root);
        fs.write("a.txt", "first".getBytes());
        BlockingQueue<dev.forge.filesystem.FileSystem.Change> changes = new LinkedBlockingQueue<>();
        var watch = fs.watch("", true, changes::offer);
        try {
            Files.writeString(root.resolve("a.txt"), "second");
            assertTrue(awaitKind(changes, dev.forge.filesystem.FileSystem.ChangeKind.CHANGED));
            Files.delete(root.resolve("a.txt"));
            assertTrue(awaitKind(changes, dev.forge.filesystem.FileSystem.ChangeKind.DELETED));
        } finally { watch.dispose(); }
    }

    @Test public void searchMultipleMatchesAndCancellation() throws Exception {
        var fs = fs(temporary.newFolder().toPath()); fs.write("a.txt", "foo foo foo\nbar".getBytes());
        var search = new SearchService(w -> fs, SearchService.SymbolSource.NONE, 100);
        var result = search.findText(W, "foo", false, true, 20, Cancellation.none());
        assertEquals(List.of(1, 5, 9), result.matches().stream().map(SearchService.TextMatch::column).toList());
        assertEquals(3, search.findText(W, "fo+", true, true, 20, Cancellation.none()).matches().size());
        Cancellation cancelled = new Cancellation(); cancelled.cancel();
        assertThrows(ForgeException.class, () -> search.findText(W, "foo", false, true, 20, cancelled.token()));
    }

    @Test public void stateQuotaSettingsAndResetPersist() throws Exception {
        Path data = temporary.newFolder().toPath();
        FileStateStore store = new FileStateStore(data, 4096, 1024); EventBus events = new EventBus();
        SettingsService settings = new SettingsService(store, events);
        settings.define(Settings.Definition.of("editor.fontSize", Settings.Type.NUMBER, 13, "Font"));
        var user = UserId.of("user"); settings.set("editor.fontSize", 18, Settings.Layer.USER, user, W);
        assertEquals(18, ((Number) settings.resolve("editor.fontSize", user, W).value()).intValue());
        assertThrows(ForgeException.class, () -> store.write(StateStore.Scope.USER, "user", Map.of("large", "x".repeat(2048))));
        settings.set("editor.fontSize", null, Settings.Layer.USER, user, W);
        assertEquals(13, ((Number) settings.resolve("editor.fontSize", user, W).value()).intValue());
    }

    @Test public void switchingDetachesOldWorkspaceAndPreservesOtherSessions() throws Exception {
        Path root = temporary.newFolder().toPath(); Files.createDirectory(root.resolve("project"));
        EventBus events = new EventBus();
        var provider = new LocalWorkspaceProvider(root, 1024 * 1024, 100, 1000);
        var workspaces = new WorkspaceService(events, List.of(provider), 2);
        var available = workspaces.available();
        var first = available.get(0).id(); var second = available.get(1).id();
        var other = SessionId.of("other");
        workspaces.open(first, S); workspaces.open(first, other);
        workspaces.release(first, S); workspaces.open(second, S);
        assertThrows(ForgeException.class, () -> workspaces.require(first, S));
        assertNotNull(workspaces.require(first, other));
        assertNotNull(workspaces.require(second, S));
        workspaces.dispose();
    }

    @Test public void concurrentEditorUpdatesHaveOneWinner() throws Exception {
        var fs = fs(temporary.newFolder().toPath()); fs.write("a.txt", "first".getBytes());
        var editors = new EditorService(new FileService(w -> fs, new EventBus(), 100000), new EventBus());
        var id = editors.open(Resource.of(W, "a.txt"), S).document().id();
        var gate = new CountDownLatch(1); var accepted = new java.util.concurrent.atomic.AtomicInteger();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> updates = new ArrayList<>();
            for (String value : List.of("one", "two")) updates.add(pool.submit(() -> {
                try { gate.await(); editors.update(id, W, S, value, 1); accepted.incrementAndGet(); }
                catch (ForgeException expected) { assertEquals(ForgeException.Code.CONFLICT, expected.code()); }
                catch (InterruptedException e) { throw new AssertionError(e); }
            }));
            gate.countDown(); for (var update : updates) update.get(2, TimeUnit.SECONDS);
            assertEquals(1, accepted.get()); assertEquals(2, editors.document(id).version());
        } finally { editors.dispose(); }
    }

    private boolean awaitKind(BlockingQueue<dev.forge.filesystem.FileSystem.Change> queue,
                              dev.forge.filesystem.FileSystem.ChangeKind expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            var change = queue.poll(100, TimeUnit.MILLISECONDS);
            if (change != null && change.kind() == expected && change.path().equals("a.txt")) return true;
        }
        return false;
    }
}
