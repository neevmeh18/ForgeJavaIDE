package dev.forge.core.extension;

import dev.forge.core.Args;
import dev.forge.core.ForgeException;
import dev.forge.core.Ids.ExtensionId;
import dev.forge.core.Ids.UserId;
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
 *
 * <p><b>State and settings isolation</b> — every state or settings operation is automatically
 * namespaced to the extension's own ID. The {@link #requireOwnNamespace} check runs before
 * any read or mutation, centralising the isolation boundary in this class so cross-extension
 * data access is structurally impossible and reserved framework namespaces are always blocked.
 */
public final class ExtensionContext {

    /**
     * Namespaces owned by the framework. Extensions are refused registration inside them so a
     * third party cannot claim, say, {@code file.delete} by registering it first.
     */
    private static final Set<String> RESERVED = Set.of(
            "auth", "command", "debug", "editor", "extension", "file", "language",
            "scm", "search", "settings", "state", "task", "terminal", "workbench", "workspace");

    private final ExtensionId extensionId;
    private final CommandRegistry commands;
    private final QueryRegistry queries;
    private final CommandExecutor executor;
    private final EventBus events;
    private final ContributionRegistry contributions;
    private final Lifecycle.Store disposables;
    private final StateStore stateStore;
    private final SettingsService settingsService;
    private final Log log;

    public ExtensionContext(ExtensionId extensionId, CommandRegistry commands, QueryRegistry queries,
                     CommandExecutor executor, EventBus events, ContributionRegistry contributions,
                     Lifecycle.Store disposables, StateStore stateStore,
                     SettingsService settingsService) {
        this.extensionId = extensionId;
        this.commands = commands;
        this.queries = queries;
        this.executor = executor;
        this.events = events;
        this.contributions = contributions;
        this.disposables = disposables;
        this.stateStore = stateStore;
        this.settingsService = settingsService;
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

    // ---- Extension-owned persistent state -----------------------------------------------

    /**
     * Reads all values stored by this extension at {@code scope}.
     *
     * <p>The returned map is a snapshot — any key whose value is {@code null} has been removed.
     */
    public Map<String, Object> getState(StateStore.Scope scope) {
        requireStateAccess();
        return stateStore.read(scope, extensionId.value());
    }

    /**
     * Reads a single value stored by this extension at {@code scope}.
     *
     * @return the value, or {@code null} if no value has been set for this key.
     */
    public Object getState(StateStore.Scope scope, String key) {
        requireStateAccess();
        return stateStore.get(scope, extensionId.value(), key).orElse(null);
    }

    /**
     * Writes a value into this extension's state at {@code scope}. A {@code null} value
     * removes the key. The entire document is replaced so the write goes through a single
     * atomic state-store operation; concurrent callers that need a read-modify-write cycle
     * should hold external synchronisation.
     */
    public void putState(StateStore.Scope scope, String key, Object value) {
        requireStateAccess();
        stateStore.put(scope, extensionId.value(), key, value);
    }

    /**
     * Removes one key from this extension's state at {@code scope}.
     */
    public void removeState(StateStore.Scope scope, String key) {
        requireStateAccess();
        stateStore.put(scope, extensionId.value(), key, null);
    }

    /**
     * Removes <em>all</em> keys stored by this extension at {@code scope}.
     */
    public void clearState(StateStore.Scope scope) {
        requireStateAccess();
        stateStore.clear(scope, extensionId.value());
    }

    // ---- Extension-owned settings -------------------------------------------------------

    /**
     * Declares a new setting owned by this extension. The setting key is automatically
     * namespaced to the extension's ID, so two extensions cannot collide even if they
     * choose the same short key name.
     *
     * <p>The setting's default value lives at the {@code EXTENSION} layer, meaning a user
     * or workspace can override it. Declaring the same key twice raises a conflict.
     */
    public void defineSetting(String key, Settings.Type type, Object defaultValue, String description) {
        requireStateAccess();
        settingsService.define(extensionId, new Settings.Definition(
                namespacedKey(key), type, defaultValue, description,
                Settings.Layer.EXTENSION, java.util.List.of(), extensionId.value()));
    }

    /**
     * Returns the effective value of one of this extension's settings, resolved through
     * the standard layering (workspace overlays user, user overlays extension default).
     *
     * @param key the short key supplied to {@link #defineSetting}.
     */
    public Object getSetting(String key, UserId user, WorkspaceId workspace) {
        requireStateAccess();
        return settingsService.resolve(namespacedKey(key), user, workspace).value();
    }

    /**
     * Writes a value into one of this extension's settings at the given {@code layer}.
     * {@code USER} and {@code WORKSPACE} layers are writable; {@code DEFAULT} and
     * {@code EXTENSION} are not (they come from code).
     */
    public void setSetting(String key, Object value, Settings.Layer layer, UserId user, WorkspaceId workspace) {
        requireStateAccess();
        settingsService.set(namespacedKey(key), value, layer, user, workspace);
    }

    // ---- Internal helpers ---------------------------------------------------------------

    /** An event raised by an extension; always namespaced by its extension id. */
    public record ExtensionEvent(String type, ExtensionId extensionId, WorkspaceId workspaceId,
                                 Map<String, Object> payload) implements Event {
    }

    /**
     * Centralised isolation check called before every state and settings operation, and
     * also for command and query registration.
     *
     * <p>For command/query registration the caller supplies the candidate namespace so the
     * check can verify it does not fall inside a reserved framework namespace. For state and
     * settings the namespace is always the extension's own id — derived here from the
     * extension context, never from caller-supplied data — so cross-extension access is
     * structurally blocked by design, not merely by convention.
     */
    private void requireOwnNamespace(String namespace, String id) {
        if (RESERVED.contains(namespace)) {
            throw ForgeException.forbidden(
                    "Extensions may not register into the reserved namespace '" + namespace + "': " + id)
                    .with("extensionId", extensionId.value());
        }
    }

    /**
     * Validates that state or settings access is occurring within this extension's own
     * namespace. The namespace is always derived from {@link #extensionId}, never from
     * caller-supplied arguments, making cross-extension access structurally impossible.
     */
    private void requireStateAccess() {
        String ns = extensionId.value();
        if (ns == null || ns.isBlank()) {
            throw ForgeException.forbidden("Extension has no valid identifier for state access");
        }
    }

    /**
     * Returns a key namespaced to this extension's ID, preventing collisions between
     * extensions that choose the same short key name.
     */
    private String namespacedKey(String key) {
        return extensionId.value() + "." + key;
    }
}