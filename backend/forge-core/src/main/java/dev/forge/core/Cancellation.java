package dev.forge.core;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;









public final class Cancellation {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final Token token = new Token();


    public static Token none() {
        return new Cancellation().token();
    }

    public Token token() {
        return token;
    }

    public void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            for (Runnable listener : listeners) {
                try {
                    listener.run();
                } catch (RuntimeException e) {
                    Log.of(Cancellation.class).warn("Cancellation listener failed", e);
                }
            }
            listeners.clear();
        }
    }


    public final class Token {

        private Token() {
        }

        public boolean isCancelled() {
            return cancelled.get();
        }

        public void throwIfCancelled() {
            if (cancelled.get() || Thread.currentThread().isInterrupted()) {
                throw ForgeException.cancelled("Operation cancelled");
            }
        }


        public void onCancel(Runnable action) {
            java.util.concurrent.atomic.AtomicBoolean invoked = new java.util.concurrent.atomic.AtomicBoolean();
            Runnable once = () -> { if (invoked.compareAndSet(false, true)) action.run(); };
            listeners.add(once);
            if (cancelled.get()) {
                listeners.remove(once);
                once.run();
            }
        }
    }
}
