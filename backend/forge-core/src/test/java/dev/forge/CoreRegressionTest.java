package dev.forge;

import dev.forge.auth.*;
import dev.forge.core.*;
import dev.forge.core.Ids.*;
import dev.forge.core.command.*;
import dev.forge.core.contrib.ContributionRegistry;
import dev.forge.core.event.EventBus;
import dev.forge.core.extension.*;
import dev.forge.core.query.QueryRegistry;
import dev.forge.settings.*;
import dev.forge.state.*;
import dev.forge.tasks.*;
import dev.forge.terminal.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class CoreRegressionTest {
    private static final WorkspaceId W = WorkspaceId.of("work-one");
    private static final SessionId S = SessionId.of("session-one");
    private RequestContext context() { return new RequestContext(UserId.of("user"), S, W, RequestContext.Origin.UI, Cancellation.none()); }

    @Test public void sessionLimitAndRevocation() throws Exception {
        SessionService sessions = new SessionService(new EventBus(), Duration.ofMinutes(5), Duration.ofHours(1), 1);
        try {
            var issued = sessions.issue(User.of("user", "User", "test"), "browser");
            assertTrue(sessions.authenticate(issued.token()).isPresent());
            assertFalse(sessions.authenticate("x".repeat(257)).isPresent());
            assertThrows(ForgeException.class, () -> sessions.issue(User.of("user", "User", "test"), "browser"));
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<?> refresh = pool.submit(() -> { for (int i = 0; i < 100; i++) sessions.authenticate(issued.token()); });
                sessions.revoke(issued.session().id(), "logout");
                refresh.get(2, TimeUnit.SECONDS);
            }
            assertFalse(sessions.authenticate(issued.token()).isPresent());
            assertEquals(0, sessions.activeSessions());
        } finally { sessions.dispose(); }
    }

    @Test public void cancellationRegistrationIsExactlyOnce() throws Exception {
        for (int i = 0; i < 50; i++) {
            Cancellation cancellation = new Cancellation();
            AtomicInteger called = new AtomicInteger();
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                var registered = pool.submit(() -> cancellation.token().onCancel(called::incrementAndGet));
                var cancelled = pool.submit(cancellation::cancel);
                registered.get(1, TimeUnit.SECONDS); cancelled.get(1, TimeUnit.SECONDS);
            }
            assertEquals(1, called.get());
        }
    }

    @Test public void argumentMetadataSurvivesBuildersAndIsEnforced() throws Exception {
        var registry = new CommandRegistry(); var events = new EventBus();
        var executor = new CommandExecutor(registry, events, CommandExecutor.Authorizer.PERMISSIVE, 8, 2);
        var descriptor = CommandDescriptor.of("sample.echo", "Test", "Echo")
                .withArguments(new CommandDescriptor.Argument("text", "string", "Text"))
                .workspaceScoped().describedAs("Echoes text").contributedBy(ExtensionId.of("sample"));
        assertEquals(1, descriptor.arguments().size());
        AtomicInteger invoked = new AtomicInteger();
        registry.register(descriptor, ctx -> { invoked.incrementAndGet(); return ctx.args().requiredString("text"); });
        try {
            assertThrows(ExecutionException.class, () -> executor.execute(descriptor.id(), Args.EMPTY, context()).result().get());
            assertEquals(0, invoked.get());
            assertEquals("hello", executor.execute(descriptor.id(), Args.of("text", "hello"), context()).result().get(2, TimeUnit.SECONDS));
        } finally { executor.dispose(); }
    }

    @Test public void extensionContributionsDisappearAndReturn() {
        var commands = new CommandRegistry(); var queries = new QueryRegistry(QueryRegistry.Authorizer.PERMISSIVE);
        var events = new EventBus(); var contributions = new ContributionRegistry();
        var executor = new CommandExecutor(commands, events, CommandExecutor.Authorizer.PERMISSIVE, 8, 2);
        var stateStore = memStore();
        var settings = new SettingsService(stateStore, events);
        var extensions = new ExtensionRegistry(commands, queries, executor, events, contributions,
                stateStore, settings);
        var id = ExtensionId.of("sample");
        AtomicInteger activation = new AtomicInteger(); AtomicInteger disposal = new AtomicInteger();
        extensions.discovered(new ExtensionDescriptor(id, "Sample", "1", "unused", "", List.of(), Map.of()), descriptor -> new Extension() {
            public void activate(ExtensionContext context) {
                activation.incrementAndGet();
                context.registerCommand(CommandDescriptor.of("sample.action", "Sample", "Action"), ctx -> "ok");
                context.contributeMenuItem("menu.view", "sample.action", "Action", "sample", 1);
                context.onDispose(disposal::incrementAndGet);
            }
        });
        try {
            extensions.activate(id);
            assertTrue(commands.find(CommandId.of("sample.action")).isPresent());
            assertEquals(1, contributions.menuItems().size());
            extensions.deactivate(id);
            assertTrue(commands.find(CommandId.of("sample.action")).isEmpty());
            assertTrue(contributions.menuItems().isEmpty());
            extensions.activate(id);
            assertTrue(commands.find(CommandId.of("sample.action")).isPresent());
            assertEquals(2, activation.get()); assertEquals(1, disposal.get());
        } finally { extensions.dispose(); executor.dispose(); }
        assertEquals(2, disposal.get());
    }

    // -----------------------------------------------------------------------
    // Extension-owned state and settings
    // -----------------------------------------------------------------------

    private static StateStore memStore() {
        return new StateStore() {
            final Map<String, Map<String, Object>> store = new ConcurrentHashMap<>();

            @Override
            public Map<String, Object> read(StateStore.Scope scope, String owner) {
                return store.getOrDefault(key(scope, owner), Map.of());
            }

            @Override
            public void write(StateStore.Scope scope, String owner, Map<String, Object> values) {
                store.put(key(scope, owner), new LinkedHashMap<>(values));
            }

            @Override
            public void clear(StateStore.Scope scope, String owner) {
                store.remove(key(scope, owner));
            }

            private static String key(StateStore.Scope scope, String owner) {
                return scope.name() + ":" + (owner == null ? "" : owner);
            }
        };
    }

    private static SettingsService settingService(StateStore stateStore, EventBus events) {
        return new SettingsService(stateStore, events);
    }

    private static ExtensionRegistry extensionRegistry(CommandRegistry commands, QueryRegistry queries,
                                                        CommandExecutor executor, EventBus events,
                                                        ContributionRegistry contributions,
                                                        StateStore stateStore, SettingsService settings) {
        return new ExtensionRegistry(commands, queries, executor, events, contributions,
                stateStore, settings);
    }

    @Test public void extensionStateStoresAndRetrievesValues() {
        var commands = new CommandRegistry(); var queries = new QueryRegistry(QueryRegistry.Authorizer.PERMISSIVE);
        var events = new EventBus(); var contributions = new ContributionRegistry();
        var executor = new CommandExecutor(commands, events, CommandExecutor.Authorizer.PERMISSIVE, 8, 2);
        var stateStore = memStore();
        var settings = settingService(stateStore, events);
        var extensions = extensionRegistry(commands, queries, executor, events, contributions, stateStore, settings);
        var extId = ExtensionId.of("test.state");
        var state = new Object() { Map<String, Object> written; };
        extensions.discovered(new ExtensionDescriptor(extId, "StateTest", "1", "", "", List.of("onStartup"), Map.of()),
                d -> new Extension() {
                    @Override public void activate(ExtensionContext ctx) {
                        // Write and read back
                        ctx.setState("greeting", "hello");
                        ctx.setState("count", 42);
                        assertEquals("hello", ctx.getState("greeting"));
                        assertEquals(42, ctx.getState("count"));
                        // getAllState
                        Map<String, Object> all = ctx.getAllState();
                        assertEquals("hello", all.get("greeting"));
                        assertEquals(42, all.get("count"));
                        // Update
                        ctx.setState("greeting", "world");
                        assertEquals("world", ctx.getState("greeting"));
                        // Delete
                        ctx.deleteState("count");
                        assertNull(ctx.getState("count"));
                    }
                });
        extensions.start();
        extensions.activate(extId);
        extensions.dispose();
        executor.dispose();
    }

    @Test public void extensionStateIsIsolatedBetweenExtensions() {
        var commands = new CommandRegistry(); var queries = new QueryRegistry(QueryRegistry.Authorizer.PERMISSIVE);
        var events = new EventBus(); var contributions = new ContributionRegistry();
        var executor = new CommandExecutor(commands, events, CommandExecutor.Authorizer.PERMISSIVE, 8, 2);
        var stateStore = memStore();
        var settings = settingService(stateStore, events);
        var extensions = extensionRegistry(commands, queries, executor, events, contributions, stateStore, settings);
        var extA = ExtensionId.of("ext.a");
        var extB = ExtensionId.of("ext.b");
        AtomicInteger activated = new AtomicInteger();
        extensions.discovered(new ExtensionDescriptor(extA, "A", "1", "", "", List.of("onStartup"), Map.of()),
                d -> new Extension() {
                    @Override public void activate(ExtensionContext ctx) {
                        ctx.setState("theme", "dark");
                        ctx.setState("zoom", 100);
                        activated.incrementAndGet();
                    }
                });
        extensions.discovered(new ExtensionDescriptor(extB, "B", "1", "", "", List.of("onStartup"), Map.of()),
                d -> new Extension() {
                    @Override public void activate(ExtensionContext ctx) {
                        ctx.setState("theme", "light");
                        assertEquals(Map.of("theme", "light"), ctx.getAllState());
                        activated.incrementAndGet();
                    }
                });
        extensions.start();
        extensions.activate(extA);
        extensions.activate(extB);
        // Each extension only sees its own state — extB's state does not contain "zoom" or extA's "theme"
        // (verified inside activate for extB). extA's data is separately scoped.
        assertEquals(2, activated.get());
        extensions.dispose();
        executor.dispose();
    }

    @Test public void extensionCannotUseReservedStateOrSettingNamespaces() {
        var commands = new CommandRegistry(); var queries = new QueryRegistry(QueryRegistry.Authorizer.PERMISSIVE);
        var events = new EventBus(); var contributions = new ContributionRegistry();
        var executor = new CommandExecutor(commands, events, CommandExecutor.Authorizer.PERMISSIVE, 8, 2);
        var stateStore = memStore();
        var settings = settingService(stateStore, events);
        var extensions = extensionRegistry(commands, queries, executor, events, contributions, stateStore, settings);
        var extId = ExtensionId.of("test.reserved");
        AtomicInteger caught = new AtomicInteger();
        extensions.discovered(new ExtensionDescriptor(extId, "ReservedTest", "1", "", "", List.of("onStartup"), Map.of()),
                d -> new Extension() {
                    @Override public void activate(ExtensionContext ctx) {
                        // Reserved namespace in state
                        assertThrows(ForgeException.class, () -> ctx.setState("file.hidden", true));
                        assertThrows(ForgeException.class, () -> ctx.setState("state.cache", "x"));
                        assertThrows(ForgeException.class, () -> ctx.setState("settings.theme", "x"));
                        // Reserved namespace in settings
                        assertThrows(ForgeException.class, () ->
                                ctx.defineSetting(Settings.Definition.of("command.autostart", Settings.Type.BOOLEAN, false, "")));
                        assertThrows(ForgeException.class, () ->
                                ctx.defineSetting(Settings.Definition.of("auth.tokenTTL", Settings.Type.NUMBER, 3600, "")));
                        // Non-reserved keys are fine
                        ctx.setState("my.custom.key", "ok");
                        ctx.defineSetting(Settings.Definition.of("my.custom.setting", Settings.Type.STRING, "default", ""));
                        assertEquals("ok", ctx.getState("my.custom.key"));
                        assertEquals("default", ctx.getSetting("my.custom.setting"));
                        // Verify forbidden codes
                        try { ctx.setState("editor.font", 12); } catch (ForgeException e) {
                            assertEquals(ForgeException.Code.FORBIDDEN, e.code()); caught.incrementAndGet();
                        }
                        // Verify invalid keys are rejected
                        assertThrows(ForgeException.class, () -> ctx.setState("", true));
                        assertThrows(ForgeException.class, () -> ctx.setState(".dot", true));
                    }
                });
        extensions.start();
        extensions.activate(extId);
        assertEquals(1, caught.get());
        extensions.dispose();
        executor.dispose();
    }

    @Test public void fastTaskCompletesAndRetainsBoundedOutput() {
        EventBus events = new EventBus();
        TerminalProvider provider = (spec, output, exit) -> {
            output.accept("hello\n" + "x".repeat(70000)); exit.accept(0);
            return session(spec, false);
        };
        TerminalService terminals = new TerminalService(provider, events, "/bin/sh");
        TaskProvider tasksProvider = new TaskProvider() {
            public String id() { return "test"; }
            public List<Task> provide(WorkspaceId workspace) { return List.of(new Task("hello", "Hello", "test", "echo", List.of("hello"), "", Map.of(), false, "test")); }
        };
        TaskService tasks = new TaskService(List.of(tasksProvider), terminals, events);
        terminals.start(); tasks.start();
        try {
            for (int i = 0; i < 10; i++) {
                var execution = tasks.run(W, "hello");
                assertEquals(TaskService.State.SUCCEEDED, execution.state());
                assertTrue(terminals.scrollback(execution.terminalId(), W).length() <= 65536);
            }
        } finally { tasks.dispose(); terminals.dispose(); }
    }

    @Test public void concurrentTerminalCreationHonorsSharedLimit() throws Exception {
        EventBus events = new EventBus();
        TerminalService terminals = new TerminalService((spec, output, exit) -> session(spec, true), events, "/bin/sh");
        AtomicInteger accepted = new AtomicInteger();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> starts = new ArrayList<>();
            for (int i = 0; i < 24; i++) starts.add(pool.submit(() -> {
                try { terminals.run(W, "echo", List.of("hello"), "", "Test", Map.of()); accepted.incrementAndGet(); }
                catch (ForgeException expected) { assertEquals(ForgeException.Code.CONFLICT, expected.code()); }
            }));
            for (var start : starts) start.get(3, TimeUnit.SECONDS);
            assertEquals(16, accepted.get());
            assertThrows(ForgeException.class, () -> terminals.create(W, "", "Shell", Map.of(), 80, 24));
        } finally { terminals.dispose(); }
    }

    private static TerminalSession session(TerminalProvider.Spec spec, boolean alive) {
        return new TerminalSession() {
            public TerminalId id() { return spec.id(); }
            public WorkspaceId workspaceId() { return spec.workspace(); }
            public void write(String data) { }
            public void resize(int columns, int rows) { }
            public void kill() { }
            public boolean isAlive() { return alive; }
        };
    }
}
