package dev.forge.core.event;

import dev.forge.core.Lifecycle.Disposable;
import dev.forge.core.Log;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * In-process publish/subscribe for completed occurrences.
 *
 * <p>A modular monolith needs decoupled notification, not a message broker: features publish
 * what happened, other features and the transport react, and nobody acquires a compile-time
 * dependency on the publisher.
 *
 * <p>Delivery is synchronous on the publishing thread and isolated per listener — a listener
 * that throws is logged and skipped, and cannot break the feature that published the event.
 */
public final class EventBus {

    private static final Log log = Log.of(EventBus.class);

    private final Map<Class<?>, List<Consumer<Event>>> typed = new ConcurrentHashMap<>();
    private final List<Consumer<Event>> all = new CopyOnWriteArrayList<>();

    @SuppressWarnings("unchecked")
    public <T extends Event> Disposable subscribe(Class<T> type, Consumer<T> listener) {
        Consumer<Event> erased = event -> listener.accept((T) event);
        typed.computeIfAbsent(type, key -> new CopyOnWriteArrayList<>()).add(erased);
        return () -> typed.getOrDefault(type, List.of()).remove(erased);
    }

    /** Used by the transport, which forwards everything it is allowed to forward. */
    public Disposable subscribeAll(Consumer<Event> listener) {
        all.add(listener);
        return () -> all.remove(listener);
    }

    public void publish(Event event) {
        for (Consumer<Event> listener : typed.getOrDefault(event.getClass(), List.of())) {
            deliver(listener, event);
        }
        for (Consumer<Event> listener : all) {
            deliver(listener, event);
        }
    }

    private void deliver(Consumer<Event> listener, Event event) {
        try {
            listener.accept(event);
        } catch (RuntimeException e) {
            log.with("eventType", event.type()).warn("Event listener failed", e);
        }
    }
}
