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
import dev.forge.state.StateStore;
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
        var store = new InMemoryStateStore();
        var settings = new SettingsService(store, events);
        var extensions = new ExtensionRegistry(commands, queries, executor, events, contributions, store, settings);
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

    // ---- Extension-owned state and settings isolation ------------------------------------

    @Test public void extensionStateRoundTripWithinOwnNamespace() {
        var store = new InMemoryStateStore();
        var settings = new SettingsService(store, new EventBus());
        var ctx = ctxFor(ExtensionId.of("alpha"), store, settings);

        // Write and read back — normal access within the extension's own namespace.
        ctx.putState(StateStore.Scope.APPLICATION, "theme", "dark");
        assertEquals("dark", ctx.getState(StateStore.Scope.APPLICATION, "theme"));

        ctx.putState(StateStore.Scope.APPLICATION, "fontSize", 14);
        assertEquals(14, ctx.getState(StateStore.Scope.APPLICATION, "fontSize"));

        // Ensure the unnamespaced store has the key under the extension's namespace.
        var raw = store.read(StateStore.Scope.APPLICATION, "alpha");
        assertTrue(raw.containsKey("theme"));
        assertTrue(raw.containsKey("fontSize"));
    }

    @Test public void extensionStateRemoveAndClearScopedToNamespace() {
        var store = new InMemoryStateStore();
        var settings = new SettingsService(store, new EventBus());
        var ctx = ctxFor(ExtensionId.of("beta"), store, settings);

        ctx.putState(StateStore.Scope.APPLICATION, "keep", "me");
        ctx.putState(StateStore.Scope.APPLICATION, "remove", "me");
        ctx.putState(StateStore.Scope.APPLICATION, "other", "also");

        // Remove a single key — others survive.
        ctx.removeState(StateStore.Scope.APPLICATION, "remove");
        assertEquals("me", ctx.getState(StateStore.Scope.APPLICATION, "keep"));
        assertNull(ctx.getState(StateStore.Scope.APPLICATION, "remove"));
        assertEquals("also", ctx.getState(StateStore.Scope.APPLICATION, "other"));

        // Clear everything for the scope.
        ctx.clearState(StateStore.Scope.APPLICATION);
        assertTrue(ctx.getState(StateStore.Scope.APPLICATION).isEmpty());
    }

    @Test public void crossExtensionStateIsolationIsEnforced() {
        var store = new InMemoryStateStore();
        var settings = new SettingsService(store, new EventBus());
        var ctxAlpha = ctxFor(ExtensionId.of("alpha"), store, settings);
        var ctxBeta  = ctxFor(ExtensionId.of("beta"), store, settings);

        // Each extension writes into its own namespace.
        ctxAlpha.putState(StateStore.Scope.APPLICATION, "color", "blue");
        ctxBeta.putState(StateStore.Scope.APPLICATION, "color", "red");

        // Each reads only its own value — no cross-extension bleed.
        assertEquals("blue", ctxAlpha.getState(StateStore.Scope.APPLICATION, "color"));
        assertEquals("red", ctxBeta.getState(StateStore.Scope.APPLICATION, "color"));

        // Underlying store shows both documents partitioned by owner.
        var alphaRaw = store.read(StateStore.Scope.APPLICATION, "alpha");
        var betaRaw  = store.read(StateStore.Scope.APPLICATION, "beta");
        assertEquals("blue", alphaRaw.get("color"));
        assertEquals("red", betaRaw.get("color"));

        // Isolation at the scope level: clearing one extension does not affect the other.
        ctxAlpha.clearState(StateStore.Scope.APPLICATION);
        assertTrue(ctxAlpha.getState(StateStore.Scope.APPLICATION).isEmpty());
        assertEquals("red", ctxBeta.getState(StateStore.Scope.APPLICATION, "color"));
    }

    @Test public void extensionStateIsolatedFromReservedNamespaces() {
        var store = new InMemoryStateStore();
        var settings = new SettingsService(store, new EventBus());
        var ctx = ctxFor(ExtensionId.of("ext1"), store, settings);

        // Extension writes under its own id, not into reserved "state" or "settings" namespaces.
        ctx.putState(StateStore.Scope.APPLICATION, "myPref", "value");
        var raw = store.read(StateStore.Scope.APPLICATION, "ext1");
        assertEquals("value", raw.get("myPref"));

        // Verify that state stored by the framework under a reserved owner is invisible.
        store.write(StateStore.Scope.APPLICATION, "state", Map.of("internal", "secret"));
        assertNull(ctx.getState(StateStore.Scope.APPLICATION, "internal"));
        // Extension's own data is untouched.
        assertEquals("value", ctx.getState(StateStore.Scope.APPLICATION, "myPref"));
    }

    @Test public void extensionStateCannotEscapeViaSessionOrUserOwners() {
        var store = new InMemoryStateStore();
        var settings = new SettingsService(store, new EventBus());
        var ctxExt = ctxFor(ExtensionId.of("ext"), store, settings);
        var userId = UserId.of("alice");
        var wsId = WorkspaceId.of("project");

        // Extension stores session and user-scoped state — these go under its own namespace.
        ctxExt.putState(StateStore.Scope.SESSION, "token", "abc");
        ctxExt.putState(StateStore.Scope.USER, "pref", "dark");

        // Another extension's context cannot see them (different owner).
        var ctxOther = ctxFor(ExtensionId.of("other"), store, settings);
        assertNull(ctxOther.getState(StateStore.Scope.SESSION, "token"));
        assertNull(ctxOther.getState(StateStore.Scope.USER, "pref"));

        // The original extension still owns the data under its namespace.
        assertEquals("abc", ctxExt.getState(StateStore.Scope.SESSION, "token"));
        assertEquals("dark", ctxExt.getState(StateStore.Scope.USER, "pref"));
    }

    @Test public void extensionSettingsDefinitionAndLayeredResolution() {
        var store = new InMemoryStateStore();
        var events = new EventBus();
        var settingsService = new SettingsService(store, events);
        var ctx = ctxFor(ExtensionId.of("editor"), store, settingsService);
        var user = UserId.of("alice");
        var ws = WorkspaceId.of("project");

        // Define a setting — key is automatically namespaced.
        ctx.defineSetting("tabSize", Settings.Type.NUMBER, 4, "Spaces per indent");

        // Read default (EXTENSION layer).
        assertEquals(4, ((Number) ctx.getSetting("tabSize", user, ws)).intValue());

        // Override at USER layer — resolution picks the user value.
        ctx.setSetting("tabSize", 8, Settings.Layer.USER, user, ws);
        assertEquals(8, ((Number) ctx.getSetting("tabSize", user, ws)).intValue());

        // Override at WORKSPACE layer — workspace wins over user.
        ctx.setSetting("tabSize", 2, Settings.Layer.WORKSPACE, user, ws);
        assertEquals(2, ((Number) ctx.getSetting("tabSize", user, ws)).intValue());
    }

    @Test public void extensionSettingNamespacingPreventsCollisions() {
        var store = new InMemoryStateStore();
        var settingsService = new SettingsService(store, new EventBus());
        var ctxA = ctxFor(ExtensionId.of("extA"), store, settingsService);
        var ctxB = ctxFor(ExtensionId.of("extB"), store, settingsService);
        var user = UserId.of("alice");
        var ws = WorkspaceId.of("project");

        // Both extensions define a setting with the same short key.
        ctxA.defineSetting("margin", Settings.Type.NUMBER, 10, "Left margin");
        ctxB.defineSetting("margin", Settings.Type.NUMBER, 20, "Left margin");

        // Each reads its own — no collision because keys are namespaced.
        assertEquals(10, ((Number) ctxA.getSetting("margin", user, ws)).intValue());
        assertEquals(20, ((Number) ctxB.getSetting("margin", user, ws)).intValue());
    }

    @Test public void reservedCommandNamespaceIsEnforced() {
        var commands = new CommandRegistry();
        var queries = new QueryRegistry(QueryRegistry.Authorizer.PERMISSIVE);
        var events = new EventBus();
        var contributions = new ContributionRegistry();
        var executor = new CommandExecutor(commands, events, CommandExecutor.Authorizer.PERMISSIVE, 8, 2);
        var store = new InMemoryStateStore();
        var settings = new SettingsService(store, events);
        var ctx = ctxFor(ExtensionId.of("evil"), store, settings,
                commands, queries, events, contributions, executor);

        // An extension cannot register into "file" (a reserved namespace).
        assertThrows(ForgeException.class,
                () -> ctx.registerCommand(CommandDescriptor.of("file.removeAll", "Evil", "Destroy"), c -> "boom"));

        // But it can register in its own or any non-reserved namespace.
        ctx.registerCommand(CommandDescriptor.of("evil.safe", "Evil", "Safe"), c -> "ok");
        assertTrue(commands.find(CommandId.of("evil.safe")).isPresent());
    }

    @Test public void stateAccessWithNonExistentKeyReturnsNull() {
        var store = new InMemoryStateStore();
        var settings = new SettingsService(store, new EventBus());
        var ctx = ctxFor(ExtensionId.of("test"), store, settings);

        assertNull(ctx.getState(StateStore.Scope.APPLICATION, "nonexistent"));
        assertTrue(ctx.getState(StateStore.Scope.APPLICATION).isEmpty());
    }

    // ---- Test helpers -------------------------------------------------------------------

    /** An in-memory StateStore for testing — no filesystem, no symlink checks. */
    private static final class InMemoryStateStore implements StateStore {
        private final Map<String, Map<String, Object>> data = new ConcurrentHashMap<>();

        private String key(Scope scope, String owner) {
            return scope.name() + ":" + (owner == null ? "" : owner);
        }

        @Override public Map<String, Object> read(Scope scope, String owner) {
            return Map.copyOf(data.getOrDefault(key(scope, owner), Map.of()));
        }

        @Override public void write(Scope scope, String owner, Map<String, Object> values) {
            data.put(key(scope, owner), new LinkedHashMap<>(values));
        }

        @Override public void clear(Scope scope, String owner) {
            data.remove(key(scope, owner));
        }

        @Override public Optional<Object> get(Scope scope, String owner, String k) {
            return Optional.ofNullable(data.getOrDefault(key(scope, owner), Map.of()).get(k));
        }

        @Override public void put(Scope scope, String owner, String k, Object value) {
            data.computeIfAbsent(key(scope, owner), x -> new LinkedHashMap<>());
            Map<String, Object> doc = data.get(key(scope, owner));
            if (value == null) {
                doc.remove(k);
            } else {
                doc.put(k, value);
            }
        }
    }

    private static ExtensionContext ctxFor(ExtensionId id, StateStore store, SettingsService settings) {
        var commands = new CommandRegistry();
        var queries = new QueryRegistry(QueryRegistry.Authorizer.PERMISSIVE);
        var events = new EventBus();
        var contributions = new ContributionRegistry();
        var executor = new CommandExecutor(commands, events, CommandExecutor.Authorizer.PERMISSIVE, 8, 2);
        return new ExtensionContext(id, commands, queries, executor, events, contributions,
                new Lifecycle.Store(), store, settings);
    }

    private static ExtensionContext ctxFor(ExtensionId id, StateStore store, SettingsService settings,
                                           CommandRegistry commands, QueryRegistry queries,
                                           EventBus events, ContributionRegistry contributions,
                                           CommandExecutor executor) {
        return new ExtensionContext(id, commands, queries, executor, events, contributions,
                new Lifecycle.Store(), store, settings);
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
