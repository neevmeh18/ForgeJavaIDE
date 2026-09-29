package dev.forge.core.extension;

import dev.forge.core.ForgeException;
import dev.forge.core.ExtensionId;
import dev.forge.core.Lifecycle;
import dev.forge.core.Log;
import dev.forge.core.command.CommandExecutor;
import dev.forge.core.command.CommandRegistry;
import dev.forge.core.contrib.ContributionRegistry;
import dev.forge.core.event.EventBus;
import dev.forge.core.query.QueryRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;













public final class ExtensionRegistry implements dev.forge.core.Component {

    private static final Log log = Log.of(ExtensionRegistry.class);









    private static final class Entry {
        final ExtensionDescriptor descriptor;
        final Loader loader;
        Lifecycle.Store disposables = new Lifecycle.Store();
        volatile State state = State.DISCOVERED;
        volatile String failure;
        volatile Extension instance;

        Entry(ExtensionDescriptor descriptor, Loader loader) {
            this.descriptor = descriptor;
            this.loader = loader;
        }
    }

    private final Map<ExtensionId, Entry> entries = new ConcurrentHashMap<>();
    private final CommandRegistry commands;
    private final QueryRegistry queries;
    private final CommandExecutor executor;
    private final EventBus events;
    private final ContributionRegistry contributions;

    public ExtensionRegistry(CommandRegistry commands, QueryRegistry queries, CommandExecutor executor,
                             EventBus events, ContributionRegistry contributions) {
        this.commands = commands;
        this.queries = queries;
        this.executor = executor;
        this.events = events;
        this.contributions = contributions;
    }

    @Override
    public void start() {

        commands.onUnresolved(id -> activateFor(ExtensionDescriptor.onCommand(id.value())));
        activateFor(ExtensionDescriptor.ON_STARTUP);
    }

    @Override
    public void dispose() {
        entries.keySet().forEach(this::deactivate);
    }

    public void discovered(ExtensionDescriptor descriptor, Loader loader) {
        if (entries.putIfAbsent(descriptor.id(), new Entry(descriptor, loader)) != null) {
            throw ForgeException.conflict("Extension already registered: " + descriptor.id());
        }
        log.with("extensionId", descriptor.id()).with("version", descriptor.version()).info("Extension discovered");
    }





    public void activateFor(String activationEvent) {
        for (Entry entry : entries.values()) {
            if (entry.state == State.DISCOVERED && entry.descriptor.activationEvents().contains(activationEvent)) {
                activate(entry.descriptor.id());
            }
        }
    }


    public void activate(ExtensionId id) {
        Entry entry = entries.get(id);
        if (entry == null) {
            throw ForgeException.notFound("Unknown extension: " + id);
        }
        synchronized (entry) {
            if (entry.state != State.DISCOVERED && entry.state != State.DEACTIVATED) {
                return;
            }


            entry.disposables = new Lifecycle.Store();
            Log scoped = log.with("extensionId", id);
            try {
                entry.instance = entry.loader.load(entry.descriptor);
                entry.state = State.LOADED;
                entry.instance.activate(new ExtensionContext(
                        id, commands, queries, executor, events, contributions, entry.disposables));
                entry.state = State.ACTIVATED;
                entry.failure = null;
                scoped.info("Extension activated");
                events.publish(new dev.forge.core.extension.ExtensionActivated(id, entry.descriptor.name()));
            } catch (Throwable t) {
                entry.state = State.FAILED;
                entry.failure = "Extension activation failed; see server logs";
                scoped.error("Extension activation failed", t);
                if (entry.instance != null) {
                    try { entry.instance.deactivate(); } catch (Throwable cleanup) { scoped.warn("Extension cleanup failed", cleanup); }
                    entry.instance = null;
                }
                rollback(entry, id);
                events.publish(new dev.forge.core.extension.ExtensionFailed(id, "activate", entry.failure));
            }
        }
    }

    public void deactivate(ExtensionId id) {
        Entry entry = entries.get(id);
        if (entry == null || entry.state != State.ACTIVATED) {
            return;
        }
        synchronized (entry) {
            if (entry.state != State.ACTIVATED) return;
            try {
                entry.instance.deactivate();
            } catch (Throwable t) {
                log.with("extensionId", id).warn("Extension deactivate() threw; continuing teardown",
                        t instanceof Exception e ? e : new RuntimeException(t));
            }
            rollback(entry, id);
            entry.instance = null;
            entry.state = State.DEACTIVATED;
            events.publish(new dev.forge.core.extension.ExtensionDeactivated(id));
        }
    }

    public List<Status> list() {
        return entries.values().stream()
                .map(entry -> new Status(entry.descriptor.id(), entry.descriptor.name(),
                        entry.descriptor.version(), entry.state, entry.failure))
                .sorted(java.util.Comparator.comparing(status -> status.id().value()))
                .toList();
    }

    public Optional<Status> status(ExtensionId id) {
        return list().stream().filter(s -> s.id().equals(id)).findFirst();
    }


    private void rollback(Entry entry, ExtensionId id) {
        entry.disposables.dispose();
        commands.unregisterAllFrom(id);
        queries.unregisterAllFrom(id);
        contributions.removeAllFrom(id);
    }
}
