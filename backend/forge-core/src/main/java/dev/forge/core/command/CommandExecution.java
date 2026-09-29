package dev.forge.core.command;

import dev.forge.core.Cancellation;
import dev.forge.core.SessionId;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;









public final class CommandExecution {



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
        state = failure.code() == dev.forge.core.Code.CANCELLED ? State.CANCELLED : State.FAILED;
        result.completeExceptionally(failure);
    }
}
