package dev.forge.core.command;

import dev.forge.core.ForgeException;
import dev.forge.core.Ids.ExtensionId;
import dev.forge.core.Lifecycle.Disposable;
import dev.forge.core.Log;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The catalogue of everything the IDE can be asked to do.
 *
 * <p>It knows how to {@code register}, {@code unregister}, {@code find} and {@code list}
 * commands with their metadata — and nothing about what any of them mean. Feature behaviour
 * lives in features; the registry stays a directory.
 *
 * <p>Four distinct operations are kept apart on purpose:
 * <ul>
 *   <li><b>registration</b> — claiming an unused id; a clash is a {@code CONFLICT}, never a
 *       silent overwrite.</li>
 *   <li><b>decoration / interception</b> — {@link CommandInterceptor}, which cannot take an
 *       id away from its owner.</li>
 *   <li><b>replacement</b> — an explicit, logged call to {@link #replace}, refused outright for
 *       commands marked {@link CommandDescriptor#asSensitive()}.</li>
 * </ul>
 */
public final class CommandRegistry {

    private static final Log log = Log.of(CommandRegistry.class);

    /** A descriptor together with the handler currently serving it. */
    public record Registration(CommandDescriptor descriptor, CommandHandler handler) {
    }

    private final Map<CommandId, Registration> commands = new ConcurrentHashMap<>();
    private final List<CommandInterceptor> interceptors = new CopyOnWriteArrayList<>();
    private final List<java.util.function.Consumer<CommandId>> resolvers = new CopyOnWriteArrayList<>();

    public Disposable register(CommandDescriptor descriptor, CommandHandler handler) {
        Registration registration = new Registration(descriptor, handler);
        Registration previous = commands.putIfAbsent(descriptor.id(), registration);
        if (previous != null) {
            throw ForgeException.conflict("Command already registered: " + descriptor.id())
                    .with("commandId", descriptor.id().value())
                    .with("owner", previous.descriptor().source());
        }
        log.with("commandId", descriptor.id()).with("source", descriptor.source()).debug("Command registered");
        return () -> commands.remove(descriptor.id(), registration);
    }

    public void unregister(CommandId id) {
        commands.remove(id);
    }

    /**
     * Deliberately swaps the handler behind an existing command. Sensitive built-ins refuse to
     * be replaced, so an extension cannot quietly become {@code file.delete} or {@code auth.login}.
     */
    public Disposable replace(CommandId id, ExtensionId by, CommandHandler handler) {
        Registration existing = commands.get(id);
        if (existing == null) {
            throw ForgeException.notFound("Cannot replace unknown command: " + id);
        }
        if (existing.descriptor().sensitive()) {
            throw ForgeException.forbidden("Command is protected and cannot be replaced: " + id)
                    .with("commandId", id.value());
        }
        commands.put(id, new Registration(existing.descriptor(), handler));
        log.with("commandId", id).with("extensionId", by).warn("Built-in command handler replaced");
        return () -> commands.put(id, existing);
    }

    public Disposable intercept(CommandInterceptor interceptor) {
        interceptors.add(interceptor);
        interceptors.sort(Comparator.comparingInt(CommandInterceptor::order));
        return () -> interceptors.remove(interceptor);
    }

    List<CommandInterceptor> interceptors() {
        return interceptors;
    }

    /**
     * Called when an id is not (yet) registered, so a lazily activated extension can claim it.
     * This is how {@code onCommand:} activation works without the executor knowing that
     * extensions exist at all.
     */
    public Disposable onUnresolved(java.util.function.Consumer<CommandId> resolver) {
        resolvers.add(resolver);
        return () -> resolvers.remove(resolver);
    }

    public Optional<Registration> find(CommandId id) {
        Registration registration = commands.get(id);
        if (registration == null && !resolvers.isEmpty()) {
            for (java.util.function.Consumer<CommandId> resolver : resolvers) {
                try {
                    resolver.accept(id);
                } catch (RuntimeException e) {
                    log.with("commandId", id).warn("Command resolver failed", e);
                }
            }
            registration = commands.get(id);
        }
        return Optional.ofNullable(registration);
    }

    public CommandDescriptor require(CommandId id) {
        return find(id).map(Registration::descriptor)
                .orElseThrow(() -> ForgeException.notFound("Unknown command: " + id).with("commandId", id.value()));
    }

    /** Every known command, sorted by id — the raw material for palettes and menus. */
    public List<CommandDescriptor> list() {
        return commands.values().stream()
                .map(Registration::descriptor)
                .sorted(Comparator.comparing(CommandDescriptor::id))
                .toList();
    }

    /** Removes everything contributed by one extension, used when it is deactivated. */
    public void unregisterAllFrom(ExtensionId extension) {
        commands.entrySet().removeIf(entry -> extension.value().equals(entry.getValue().descriptor().source()));
    }
}
