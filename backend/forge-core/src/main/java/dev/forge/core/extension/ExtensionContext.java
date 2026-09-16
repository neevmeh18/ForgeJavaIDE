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
    private final Log log;

    ExtensionContext(ExtensionId extensionId, CommandRegistry commands, QueryRegistry queries,
                     CommandExecutor executor, EventBus events, ContributionRegistry contributions,
                     Lifecycle.Store disposables) {
        this.extensionId = extensionId;
        this.commands = commands;
        this.queries = queries;
        this.executor = executor;
        this.events = events;
        this.contributions = contributions;
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
}
