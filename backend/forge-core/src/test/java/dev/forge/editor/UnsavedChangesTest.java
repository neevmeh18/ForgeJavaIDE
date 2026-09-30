package dev.forge.editor;

import dev.forge.core.Args;
import dev.forge.core.ForgeException;
import dev.forge.core.Ids.SessionId;
import dev.forge.core.Ids.UserId;
import dev.forge.core.Ids.WorkspaceId;
import dev.forge.core.RequestContext;
import dev.forge.core.command.CommandRegistry;
import dev.forge.core.contrib.ContributionRegistry;
import dev.forge.core.event.EventBus;
import dev.forge.core.query.QueryRegistry;
import dev.forge.filesystem.FileService;
import dev.forge.filesystem.FileSystem;
import dev.forge.filesystem.Resource;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UnsavedChangesTest {
    private final WorkspaceId workspace = WorkspaceId.of("workspace");
    private final WorkspaceId otherWorkspace = WorkspaceId.of("other-workspace");
    private final SessionId session = SessionId.of("session");
    private final SessionId otherSession = SessionId.of("other-session");
    private final Map<WorkspaceId, Map<String, byte[]>> disk = new HashMap<>();
    private EditorService editors;
    private FileService files;
    private QueryRegistry queries;

    @BeforeEach
    void setUp() {
        EventBus events = new EventBus();
        files = new FileService(this::fileSystem, events, 1024 * 1024);
        editors = new EditorService(files, events);
        editors.start();
        queries = new QueryRegistry(QueryRegistry.Authorizer.PERMISSIVE);
        new EditorCommands(editors, null).register(new CommandRegistry(), queries, new ContributionRegistry());
    }

    @AfterEach
    void tearDown() {
        editors.dispose();
    }

    @Test
    void closedDirtyDraftCanBeListedPreviewedAndReopenedWithoutReadingDisk() {
        var opened = open(workspace, "Main.java", session);
        var changed = editors.update(opened.document().id(), "unsaved draft\n", 1);
        editors.close(changed.id(), session);

        assertEquals(List.of(), query("editor.documents", workspace, session, Args.EMPTY));
        assertEquals(List.of(changed), query("editor.unsavedDocuments", workspace, session, Args.EMPTY));
        var preview = (EditorService.OpenDocument) query("editor.document", workspace, session,
                new Args(Map.of("documentId", changed.id().value())));
        assertEquals(changed, preview.document());
        assertEquals("unsaved draft\n", preview.text());
        assertEquals("on disk", new String(disk.get(workspace).get("Main.java"), StandardCharsets.UTF_8));

        // Removing the backing file proves reopening uses the retained buffer, not storage.
        disk.get(workspace).remove("Main.java");
        var restored = editors.open(Resource.of(workspace, "Main.java"), session);
        assertEquals(preview, restored);
        assertEquals(List.of(changed), editors.documentsFor(workspace, session));
    }

    @Test
    void listsOnlyDirtyBuffersInTheRequestedWorkspaceRegardlessOfViewer() {
        var z = open(workspace, "z.java", session).document();
        var a = open(workspace, "a.java", otherSession).document();
        open(workspace, "clean.java", session);
        var foreign = open(otherWorkspace, "a.java", session).document();
        z = editors.update(z.id(), "z draft", 1);
        a = editors.update(a.id(), "a draft", 1);
        foreign = editors.update(foreign.id(), "foreign draft", 1);
        editors.close(z.id(), session);

        assertEquals(List.of(a, z), query("editor.unsavedDocuments", workspace, session, Args.EMPTY));
        assertEquals(List.of(a, z), query("editor.unsavedDocuments", workspace, otherSession, Args.EMPTY));
        assertEquals(List.of(foreign), editors.unsavedDocumentsFor(otherWorkspace));
        assertEquals(List.of(a), editors.documentsFor(workspace, otherSession));
    }

    @Test
    void aSavedRetainedDraftStopsAppearingWithoutChangingSaveBehavior() {
        var document = open(workspace, "saved.java", session).document();
        editors.update(document.id(), "draft", 1);
        editors.close(document.id(), session);
        files.writeText(Resource.of(workspace, document.path()), "draft");

        assertEquals(List.of(), editors.unsavedDocumentsFor(workspace));
        assertFalse(editors.document(document.id()).dirty());
        assertEquals("draft", files.readText(Resource.of(workspace, document.path())).text());
    }

    @Test
    void deletedDraftsAndClosedCleanDocumentsDoNotAppear() {
        var clean = open(workspace, "clean.java", session).document();
        editors.close(clean.id(), session);
        assertThrows(ForgeException.class, () -> editors.document(clean.id()));
        var dirty = open(workspace, "deleted.java", session).document();
        editors.update(dirty.id(), "draft", 1);
        editors.close(dirty.id(), session);
        files.delete(Resource.of(workspace, dirty.path()), false);

        assertEquals(List.of(), editors.unsavedDocumentsFor(workspace));
        assertThrows(ForgeException.class, () -> editors.document(dirty.id()));
    }

    @Test
    void unsavedQueryRequiresAuthenticationAndWorkspace() {
        assertThrows(ForgeException.class,
                () -> queries.execute("editor.unsavedDocuments", Args.EMPTY, RequestContext.system()));
        assertThrows(ForgeException.class,
                () -> query("editor.unsavedDocuments", null, session, Args.EMPTY));
        assertEquals(List.of(), query("editor.unsavedDocuments", workspace, session, Args.EMPTY));
    }

    private Object query(String id, WorkspaceId ws, SessionId viewer, Args args) {
        return queries.execute(id, args,
                new RequestContext(UserId.of("user"), viewer, ws, RequestContext.Origin.UI, null));
    }

    private EditorService.OpenDocument open(WorkspaceId ws, String path, SessionId viewer) {
        disk.computeIfAbsent(ws, ignored -> new HashMap<>())
                .put(path, "on disk".getBytes(StandardCharsets.UTF_8));
        return editors.open(Resource.of(ws, path), viewer);
    }

    /** Only the storage operations exercised by the editor are supported by this fixture. */
    private FileSystem fileSystem(WorkspaceId ws) {
        Map<String, byte[]> entries = disk.computeIfAbsent(ws, ignored -> new HashMap<>());
        return (FileSystem) Proxy.newProxyInstance(FileSystem.class.getClassLoader(),
                new Class<?>[]{FileSystem.class}, (proxy, method, args) -> {
                    String path = (String) args[0];
                    return switch (method.getName()) {
                        case "stat" -> new FileSystem.Stat(path, false, entries.get(path).length, 0, false);
                        case "read" -> entries.get(path);
                        case "exists" -> entries.containsKey(path);
                        case "write" -> { entries.put(path, (byte[]) args[1]); yield null; }
                        case "delete" -> { entries.remove(path); yield null; }
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
    }
}
