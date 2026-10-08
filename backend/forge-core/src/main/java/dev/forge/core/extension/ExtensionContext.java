package dev.forge.core.extension;

import dev.forge.core.Args;
import dev.forge.core.ForgeException;
import dev.forge.core.Ids.ExtensionId;
import dev.forge.core.Ids.WorkspaceId;
import dev.forge.core.Lifecycle;
import dev.forge.core.Log;
import dev.forge.core.RequestContext;
import dev.forge.core.command.CommandDescriptor;
import dev.forge.core.command.CommandExecution;
import dev.forge.core.command.CommandExecutor;
import dev.forge.core.command.CommandHandler;
import dev.forge.core.command.CommandId;
import dev.forge.core.command.CommandInterceptor;
import dev.forge.core.command.CommandRegistry;
import dev.forge.core.contrib.ContributionRegistry;
import dev.forge.core.event.Event;
import dev.forge.core.event.EventBus;
import dev.forge.core.query.QueryRegistry;
import dev.forge.settings.Settings;
import dev.forge.settings.SettingsService;
import dev.forge.state.StateStore;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * <b>The public Extension API.</b> This class is the entire surface an extension may program
 * against; everything else in the framework is internal and may change.
 *
 * <p>Notice what is absent: no {@code WorkspaceService}, no {@code FileSystem}, no transport, no
 * database. An extension reaches features the same way the frontend and the CLI do — by
 * executing commands and running queries — so it gets the same validation and authorisation, and
 * the framework can evolve a feature's internals without breaking installed extensions.
 *
 * <p>Registrations made here are tracked and released automatically on deactivation.
 */
public final class ExtensionContext {

    /**
     * Namespaces owned by the framework. Extensions are refused registration inside them so a
     * third party cannot claim, say, {@code file.delete} by registering it first.
     */
    static final Set<String> RESERVED = Set.of(
            "auth", "command", "debug", "editor", "extension", "file", "language",
            "scm", "search", "settings", "state", "task", "terminal", "workbench", "workspace");

    /** Valid state/setting key: starts with a letter or digit, then letters/digits/dots/underscores/hyphens. */
    private static final Pattern VALID_KEY = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}");

    private final ExtensionId extensionId;
    private final CommandRegistry commands;
    private final QueryRegistry queries;
    private final CommandExecutor executor;
    private final EventBus events;
    private final ContributionRegistry contributions;
    private final StateStore stateStore;
    private final SettingsService settingsService;
    private final Lifecycle.Store disposables;
    private final Log log;

    ExtensionContext(ExtensionId extensionId, CommandRegistry commands, QueryRegistry queries,
                     CommandExecutor executor, EventBus events, ContributionRegistry contributions,
                     StateStore stateStore, SettingsService settingsService,
                     Lifecycle.Store disposables) {
        this.extensionId = extensionId;
        this.commands = commands;
        this.queries = queries;
        this.executor = executor;
        this.events = events;
        this.contributions = contributions;
        this.stateStore = stateStore;
        this.settingsService = settingsService;
        this.disposables = disposables;
        this.log = Log.of(Extension.class).with("extensionId", extensionId);
    }

    public ExtensionId id() {
        return extensionId;
    }

    public Log log() {
        return log;
    }

    /** Registers a command. The id must live outside the framework's reserved namespaces. */
    public void registerCommand(CommandDescriptor descriptor, CommandHandler handler) {
        requireOwnNamespace(descriptor.id().namespace(), descriptor.id().value());
        disposables.add(commands.register(descriptor.contributedBy(extensionId), handler));
    }

    public void registerQuery(QueryRegistry.QueryDescriptor descriptor, QueryRegistry.QueryHandler handler) {
        int dot = descriptor.id().indexOf('.');
        requireOwnNamespace(dot < 0 ? descriptor.id() : descriptor.id().substring(0, dot), descriptor.id());
        disposables.add(queries.register(descriptor.contributedBy(extensionId), handler));
    }

    /**
     * Decorates existing commands without taking them over. Replacement of a built-in is a
     * separate, restricted operation and is not offered here.
     */
    public void intercept(CommandInterceptor interceptor) {
        disposables.add(commands.intercept(interceptor));
    }

    /** Runs any command the caller in {@code ctx} is entitled to run. */
    public CommandExecution executeCommand(String commandId, Args args, RequestContext ctx) {
        return executor.execute(CommandId.of(commandId), args, ctx.withOrigin(RequestContext.Origin.EXTENSION));
    }

    public Object runQuery(String queryId, Args args, RequestContext ctx) {
        return queries.execute(queryId, args, ctx.withOrigin(RequestContext.Origin.EXTENSION));
    }

    public <T extends Event> void subscribe(Class<T> type, Consumer<T> listener) {
        disposables.add(events.subscribe(type, listener));
    }

    public void subscribeAll(Consumer<Event> listener) {
        disposables.add(events.subscribeAll(listener));
    }

    /**
     * Publishes an extension-scoped event. The type is forced into the extension's own
     * namespace so an extension cannot forge a framework event such as {@code file.saved}.
     */
    public void publish(String type, WorkspaceId workspaceId, Map<String, Object> payload) {
        events.publish(new ExtensionEvent(extensionId.value() + "." + type, extensionId, workspaceId,
                payload == null ? Map.of() : Map.copyOf(payload)));
    }

    public void contributeMenuItem(String menu, String commandId, String title, String group, int order) {
        disposables.add(contributions.addMenuItem(new ContributionRegistry.MenuItem(
                menu, CommandId.of(commandId), title, group, order, extensionId.value())));
    }

    public void contributeKeybinding(String key, String commandId, String when) {
        disposables.add(contributions.addKeybinding(new ContributionRegistry.Keybinding(
                key, CommandId.of(commandId), when, extensionId.value())));
    }

    public void contributeView(String id, String title, String container, String icon, int order) {
        if (!java.util.Set.of("explorer", "search", "scm", "extensions", "settings", "debug", "terminal", "problems", "tasks").contains(id))
            throw ForgeException.unsupported("Custom view renderers are not supported; use a supported built-in view id");
        disposables.add(contributions.addView(new ContributionRegistry.View(
                id, title, container, icon, order, extensionId.value())));
    }

    /** Releases a resource when the extension is deactivated. */
    public void onDispose(Lifecycle.Disposable disposable) {
        disposables.add(disposable);
    }

    // -----------------------------------------------------------------------
    // Extension-owned state
    // -----------------------------------------------------------------------

    /**
     * Returns the value stored for {@code key} in this extension's own state, or null
     * if no value has been set.
     *
     * @throws ForgeException with {@code FORBIDDEN} if the key namespace is reserved
     * @throws ForgeException with {@code INVALID_ARGUMENT} if the key format is invalid
     */
    public Object getState(String key) {
        requireValidKey(key);
        requireOwnNamespace(namespace(key), key);
        return stateStore.get(StateStore.Scope.APPLICATION, extensionId.value(), key).orElse(null);
    }

    /**
     * Stores {@code value} under {@code key} in this extension's own state. Passing
     * null is equivalent to {@link #deleteState}.
     *
     * @throws ForgeException with {@code FORBIDDEN} if the key namespace is reserved
     * @throws ForgeException with {@code INVALID_ARGUMENT} if the key format is invalid
     */
    public void setState(String key, Object value) {
        requireValidKey(key);
        requireOwnNamespace(namespace(key), key);
        stateStore.put(StateStore.Scope.APPLICATION, extensionId.value(), key, value);
    }

    /**
     * Removes {@code key} from this extension's state. Does nothing if the key
     * does not exist.
     *
     * @throws ForgeException with {@code FORBIDDEN} if the key namespace is reserved
     * @throws ForgeException with {@code INVALID_ARGUMENT} if the key format is invalid
     */
    public void deleteState(String key) {
        requireValidKey(key);
        requireOwnNamespace(namespace(key), key);
        stateStore.put(StateStore.Scope.APPLICATION, extensionId.value(), key, null);
    }

    /**
     * Returns every key/value pair stored in this extension's state. The returned map
     * is unmodifiable; changes must be made through {@link #setState} / {@link #deleteState}.
     */
    public Map<String, Object> getAllState() {
        return stateStore.read(StateStore.Scope.APPLICATION, extensionId.value());
    }

    /**
     * Removes all state belonging to this extension.
     */
    public void clearState() {
        stateStore.clear(StateStore.Scope.APPLICATION, extensionId.value());
    }

    // -----------------------------------------------------------------------
    // Extension-owned settings
    // -----------------------------------------------------------------------

    /**
     * Declares a typed setting contributed by this extension. The setting's key must
     * not belong to a reserved framework namespace.
     *
     * <p>If a setting with the same key has already been declared, a
     * {@code CONFLICT} error is raised — the extension should use a more specific
     * key or the {@link #setState state} API for mutable per-extension values.
     *
     * <p>The definition is automatically removed when the extension is deactivated.
     *
     * @throws ForgeException with {@code FORBIDDEN} if the key namespace is reserved
     * @throws ForgeException with {@code CONFLICT} if the key is already registered
     */
    public void defineSetting(Settings.Definition definition) {
        String key = definition.key();
        requireValidKey(key);
        requireOwnNamespace(namespace(key), key);
        onDispose(settingsService.define(extensionId, definition));
    }

    /**
     * Returns the resolved value of the setting identified by {@code key}, or the
     * extension's declared default when no user or workspace override exists.
     *
     * <p>To obtain the fully resolved value including current user and workspace
     * overrides, use {@link #runQuery runQuery("settings.resolved", ...)} with
     * the appropriate {@link RequestContext}.
     *
     * @throws ForgeException with {@code FORBIDDEN} if the key namespace is reserved
     * @throws ForgeException with {@code NOT_FOUND} if the setting has not been defined
     */
    public Object getSetting(String key) {
        requireValidKey(key);
        requireOwnNamespace(namespace(key), key);
        return settingsService.resolve(key, null, null).value();
    }

    /** An event raised by an extension; always namespaced by its extension id. */
    public record ExtensionEvent(String type, ExtensionId extensionId, WorkspaceId workspaceId,
                                 Map<String, Object> payload) implements Event {
    }

    private void requireOwnNamespace(String namespace, String id) {
        if (RESERVED.contains(namespace)) {
            throw ForgeException.forbidden(
                    "Extensions may not register into the reserved namespace '" + namespace + "': " + id)
                    .with("extensionId", extensionId.value());
        }
    }

    /** The first component of a dotted key, or the key itself if there is no dot. */
    private static String namespace(String key) {
        int dot = key.indexOf('.');
        return dot < 0 ? key : key.substring(0, dot);
    }

    private static void requireValidKey(String key) {
        if (!VALID_KEY.matcher(key).matches()) {
            throw ForgeException.invalidArgument("Invalid state/setting key: " + key);
        }
    }
}
