package dev.forge.core;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Disposal and startup/shutdown participation.
 *
 * <p>Almost everything in the framework that holds a resource (a watcher, a terminal, an event
 * subscription, an extension) only needs {@link Disposable}. {@link Lifecycle} is for the few
 * components the application itself starts and stops.
 */
public final class Lifecycle {

    private Lifecycle() {
    }

    @FunctionalInterface
    public interface Disposable {
        void dispose();
    }

    /** Implemented by components the application bootstrap explicitly starts and stops. */
    public interface Component extends Disposable {
        void start();

        @Override
        default void dispose() {
        }
    }

    /**
     * Collects disposables and releases them in reverse order. Used wherever a scope owns
     * several resources (a session, an extension activation, a workspace).
     */
    public static final class Store implements Disposable {

        private final Deque<Disposable> items = new ArrayDeque<>();
        private boolean disposed;

        public synchronized <T extends Disposable> T add(T item) {
            if (disposed) {
                item.dispose();
            } else {
                items.push(item);
            }
            return item;
        }

        @Override
        public synchronized void dispose() {
            disposed = true;
            while (!items.isEmpty()) {
                Disposable item = items.pop();
                try {
                    item.dispose();
                } catch (RuntimeException e) {
                    Log.of(Store.class).warn("Disposal failed", e);
                }
            }
        }
    }
}
