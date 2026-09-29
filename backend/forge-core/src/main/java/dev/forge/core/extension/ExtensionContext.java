package dev.forge.core.extension;

import dev.forge.core.Args;
import dev.forge.core.ForgeException;
import dev.forge.core.ExtensionId;
import dev.forge.core.WorkspaceId;
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












public final class ExtensionContext {





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


    public void registerCommand(CommandDescriptor descriptor, CommandHandler handler) {
        requireOwnNamespace(descriptor.id().namespace(), descriptor.id().value());
        disposables.add(commands.register(descriptor.contributedBy(extensionId), handler));
    }

    public void registerQuery(dev.forge.core.query.QueryDescriptor descriptor, dev.forge.core.query.QueryHandler handler) {
        int dot = descriptor.id().indexOf('.');
        requireOwnNamespace(dot < 0 ? descriptor.id() : descriptor.id().substring(0, dot), descriptor.id());
        disposables.add(queries.register(descriptor.contributedBy(extensionId), handler));
    }





    public void intercept(CommandInterceptor interceptor) {
        disposables.add(commands.intercept(interceptor));
    }


    public CommandExecution executeCommand(String commandId, Args args, RequestContext ctx) {
        return executor.execute(CommandId.of(commandId), args, ctx.withOrigin(dev.forge.core.Origin.EXTENSION));
    }

    public Object runQuery(String queryId, Args args, RequestContext ctx) {
        return queries.execute(queryId, args, ctx.withOrigin(dev.forge.core.Origin.EXTENSION));
    }

    public <T extends Event> void subscribe(Class<T> type, Consumer<T> listener) {
        disposables.add(events.subscribe(type, listener));
    }

    public void subscribeAll(Consumer<Event> listener) {
        disposables.add(events.subscribeAll(listener));
    }





    public void publish(String type, WorkspaceId workspaceId, Map<String, Object> payload) {
        events.publish(new ExtensionEvent(extensionId.value() + "." + type, extensionId, workspaceId,
                payload == null ? Map.of() : Map.copyOf(payload)));
    }

    public void contributeMenuItem(String menu, String commandId, String title, String group, int order) {
        disposables.add(contributions.addMenuItem(new dev.forge.core.contrib.MenuItem(
                menu, CommandId.of(commandId), title, group, order, extensionId.value())));
    }

    public void contributeKeybinding(String key, String commandId, String when) {
        disposables.add(contributions.addKeybinding(new dev.forge.core.contrib.Keybinding(
                key, CommandId.of(commandId), when, extensionId.value())));
    }

    public void contributeView(String id, String title, String container, String icon, int order) {
        if (!java.util.Set.of("explorer", "search", "scm", "extensions", "settings", "debug", "terminal", "problems", "tasks").contains(id))
            throw ForgeException.unsupported("Custom view renderers are not supported; use a supported built-in view id");
        disposables.add(contributions.addView(new dev.forge.core.contrib.View(
                id, title, container, icon, order, extensionId.value())));
    }


    public void onDispose(dev.forge.core.Disposable disposable) {
        disposables.add(disposable);
    }




    private void requireOwnNamespace(String namespace, String id) {
        if (RESERVED.contains(namespace)) {
            throw ForgeException.forbidden(
                    "Extensions may not register into the reserved namespace '" + namespace + "': " + id)
                    .with("extensionId", extensionId.value());
        }
    }
}
