package dev.forge.core.extension;

import dev.forge.core.ForgeException;
import dev.forge.core.Ids.ExtensionId;
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

/**
 * Tracks extensions through their lifecycle and activates them lazily.
 *
 * <p>States: {@code DISCOVERED -> LOADED -> ACTIVATED -> DEACTIVATED}, with {@code FAILED}
 * reachable from loading or activation. A failure is contained: the extension is marked failed,
 * its registrations are rolled back, an {@code extension.failed} event is published, and the
 * IDE carries on. One bad extension must never take the workbench down with it.
 *
 * <p>Discovery and class loading are infrastructure concerns and stay behind {@link Loader}, so
 * this class works the same for jars on disk, a future extension host process, or extensions
 * bundled into a cloud image.
 */
public final class ExtensionRegistry implements Lifecycle.Component {

    private static final Log log = Log.of(ExtensionRegistry.class);

    public enum State {
        DISCOVERED,
        LOADED,
        ACTIVATED,
        DEACTIVATED,
        FAILED
    }

    /** Turns a manifest into an instance. Implemented by infrastructure (jar class loading). */
    @FunctionalInterface
    public interface Loader {
        Extension load(ExtensionDescriptor descriptor) throws Exception;
    }

    /** What the workbench shows about an extension. */
    public record Status(ExtensionId id, String name, String version, State state, String failure) {
    }

    private static final class Entry {
        final ExtensionDescriptor descriptor;
        final Loader loader;
        final Lifecycle.Store disposables = new Lifecycle.Store();
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
        // Wire lazy activation: an unknown command id gives extensions a chance to claim it.
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

    /**
     * Activates every extension whose manifest declares {@code activationEvent}. Used for
     * {@code onStartup}, {@code onWorkspace}, {@code onLanguage:*} and {@code onCommand:*}.
     */
    public void activateFor(String activationEvent) {
        for (Entry entry : entries.values()) {
            if (entry.state == State.DISCOVERED && entry.descriptor.activationEvents().contains(activationEvent)) {
                activate(entry.descriptor.id());
            }
        }
    }

    /** Idempotent: activating an already-active or failed extension does nothing. */
    public void activate(ExtensionId id) {
        Entry entry = entries.get(id);
        if (entry == null) {
            throw ForgeException.notFound("Unknown extension: " + id);
        }
        synchronized (entry) {
            if (entry.state != State.DISCOVERED && entry.state != State.DEACTIVATED) {
                return;
            }
            Log scoped = log.with("extensionId", id);
            try {
                entry.instance = entry.loader.load(entry.descriptor);
                entry.state = State.LOADED;
                entry.instance.activate(new ExtensionContext(
                        id, commands, queries, executor, events, contributions, entry.disposables));
                entry.state = State.ACTIVATED;
                entry.failure = null;
                scoped.info("Extension activated");
                events.publish(new ExtensionEvents.ExtensionActivated(id, entry.descriptor.name()));
            } catch (Throwable t) {
                entry.state = State.FAILED;
                entry.failure = t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage());
                scoped.error("Extension activation failed", t);
                rollback(entry, id);
                events.publish(new ExtensionEvents.ExtensionFailed(id, "activate", entry.failure));
            }
        }
    }

    public void deactivate(ExtensionId id) {
        Entry entry = entries.get(id);
        if (entry == null || entry.state != State.ACTIVATED) {
            return;
        }
        synchronized (entry) {
            try {
                entry.instance.deactivate();
            } catch (Throwable t) {
                log.with("extensionId", id).warn("Extension deactivate() threw; continuing teardown",
                        t instanceof Exception e ? e : new RuntimeException(t));
            }
            rollback(entry, id);
            entry.instance = null;
            entry.state = State.DEACTIVATED;
            events.publish(new ExtensionEvents.ExtensionDeactivated(id));
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

    /** Removes everything an extension contributed, whether it deactivated cleanly or not. */
    private void rollback(Entry entry, ExtensionId id) {
        entry.disposables.dispose();
        commands.unregisterAllFrom(id);
        queries.unregisterAllFrom(id);
        contributions.removeAllFrom(id);
    }
}
