package dev.forge.core.command;

import dev.forge.core.Cancellation;
import dev.forge.core.SessionId;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

public enum State {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}
