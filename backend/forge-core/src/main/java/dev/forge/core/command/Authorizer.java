package dev.forge.core.command;

import dev.forge.core.Args;
import dev.forge.core.Cancellation;
import dev.forge.core.ForgeException;
import dev.forge.core.Ids;
import dev.forge.core.Lifecycle;
import dev.forge.core.Log;
import dev.forge.core.RequestContext;
import dev.forge.core.event.EventBus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@FunctionalInterface
public interface Authorizer {
    void authorize(CommandDescriptor descriptor, CommandContext ctx);

    Authorizer PERMISSIVE = (descriptor, ctx) -> {
    };
}
