package dev.forge.core;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Framework-neutral cancellation.
 *
 * <p>Deliberately not tied to an HTTP connection, a thread interrupt or a {@code Future}: a
 * command may be cancelled from the command palette, by a CLI, by an extension, or because a
 * collaborating user closed the workspace. Long-running work polls {@link Token#throwIfCancelled()}
 * or registers a listener to release resources.
 */
public final class Cancellation {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final Token token = new Token();

    /** A token that never cancels; for callers that have nothing to cancel. */
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

    /** The read side, safe to hand to untrusted-ish code such as extensions. */
    public final class Token {

        private Token() {
        }

        public boolean isCancelled() {
            return cancelled.get();
        }

        public void throwIfCancelled() {
            if (cancelled.get()) {
                throw ForgeException.cancelled("Operation cancelled");
            }
        }

        /** Runs {@code action} on cancellation, or immediately if already cancelled. */
        public void onCancel(Runnable action) {
            if (cancelled.get()) {
                action.run();
            } else {
                listeners.add(action);
            }
        }
    }
}
