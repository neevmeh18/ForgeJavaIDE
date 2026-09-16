package dev.forge.core.command;

import dev.forge.core.Cancellation;
import dev.forge.core.Ids.SessionId;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * A single in-flight or finished command run.
 *
 * <p>Commands are never assumed to complete immediately: cloning a workspace, searching a large
 * tree, running a build and fetching from a remote all take time. A caller that cares can hold
 * the handle, observe the {@link State}, and cancel through the same framework-neutral token
 * the handler is polling.
 */
public final class CommandExecution {

    public enum State {
        QUEUED,
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    private final String id;
    private final CommandId commandId;
    private final Instant startedAt;
    private final Cancellation cancellation;
    private final SessionId sessionId;
    private final CompletableFuture<Object> result = new CompletableFuture<>();
    private volatile State state = State.QUEUED;

    CommandExecution(String id, CommandId commandId, Cancellation cancellation, SessionId sessionId) {
        this.id = id;
        this.commandId = commandId;
        this.cancellation = cancellation;
        this.sessionId = sessionId;
        this.startedAt = Instant.now();
    }

    public String id() {
        return id;
    }

    public CommandId commandId() {
        return commandId;
    }

    public State state() {
        return state;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public SessionId sessionId() {
        return sessionId;
    }

    /** Completes with the command's typed result, or fails with a {@code ForgeException}. */
    public CompletableFuture<Object> result() {
        return result;
    }

    public void cancel() {
        cancellation.cancel();
    }

    void markRunning() {
        state = State.RUNNING;
    }

    void complete(Object value) {
        state = State.COMPLETED;
        result.complete(value);
    }

    void fail(dev.forge.core.ForgeException failure) {
        state = failure.code() == dev.forge.core.ForgeException.Code.CANCELLED ? State.CANCELLED : State.FAILED;
        result.completeExceptionally(failure);
    }
}
