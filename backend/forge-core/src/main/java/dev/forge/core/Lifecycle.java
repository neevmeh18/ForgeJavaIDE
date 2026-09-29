package dev.forge.core;

import java.util.ArrayDeque;
import java.util.Deque;








public final class Lifecycle {

    private Lifecycle() {
    }










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
