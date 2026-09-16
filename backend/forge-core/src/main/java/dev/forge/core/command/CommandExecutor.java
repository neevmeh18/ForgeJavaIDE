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

/**
 * The one place a command actually runs.
 *
 * <p>Registration says what exists; execution says what is allowed to happen. Keeping them
 * apart gives every command — built-in, extension-contributed, invoked from a shortcut or from
 * a script — the same treatment: argument and precondition checks, authorisation, cancellation,
 * tracing and error normalisation, applied once here instead of being re-implemented per handler.
 *
 * <p>Every execution is asynchronous. Handlers run on virtual threads, so a handler that blocks
 * on I/O costs a stack rather than a platform thread, and a slow command never stalls the rest
 * of the IDE.
 */
public final class CommandExecutor implements Lifecycle.Component {

    private static final Log log = Log.of(CommandExecutor.class);

    /**
     * The authorisation decision point. Supplied by the product so that {@code core} never
     * depends on the authentication feature; see {@code dev.forge.auth.Authorizer}.
     */
    @FunctionalInterface
    public interface Authorizer {
        void authorize(CommandDescriptor descriptor, CommandContext ctx);

        Authorizer PERMISSIVE = (descriptor, ctx) -> {
        };
    }

    private final CommandRegistry registry;
    private final EventBus events;
    private final Authorizer authorizer;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, CommandExecution> running = new ConcurrentHashMap<>();
    private final AtomicInteger runningCount = new AtomicInteger();
    private final Map<dev.forge.core.Ids.SessionId, AtomicInteger> runningBySession = new ConcurrentHashMap<>();
    private final int maxRunning;
    private final int maxRunningPerSession;

    public CommandExecutor(CommandRegistry registry, EventBus events, Authorizer authorizer,
                           int maxRunning, int maxRunningPerSession) {
        this.registry = registry;
        this.events = events;
        this.authorizer = authorizer;
        this.maxRunning = maxRunning;
        this.maxRunningPerSession = maxRunningPerSession;
    }

    @Override
    public void start() {
        log.info("Command executor ready");
    }

    @Override
    public void dispose() {
        workers.shutdownNow();
    }

    /**
     * Starts {@code id} and returns immediately with a handle. Preconditions are checked before
     * anything is scheduled, so an unauthorised or unavailable command never reaches a handler.
     */
    public CommandExecution execute(CommandId id, Args args, RequestContext request) {
        if (!request.isAuthenticated() && request.origin() != RequestContext.Origin.SYSTEM && !"auth.login".equals(id.value()))
            throw ForgeException.unauthorized("Authentication required");
        Cancellation cancellation = new Cancellation();
        request.cancellation().onCancel(cancellation::cancel);

        String executionId = Ids.random("exec");
        CommandExecution execution = new CommandExecution(executionId, id, cancellation, request.sessionId());
        RequestContext scoped = new RequestContext(request.userId(), request.sessionId(),
                request.workspaceId(), request.origin(), cancellation.token());
        CommandContext ctx = new CommandContext(id, args == null ? Args.EMPTY : args, scoped, executionId);

        CommandRegistry.Registration registration;
        try {
            registration = registry.find(id)
                    .orElseThrow(() -> ForgeException.notFound("Unknown command: " + id).with("commandId", id.value()));
            check(registration.descriptor(), ctx);
        } catch (RuntimeException e) {
            return failFast(execution, ctx, ForgeException.normalize(e));
        }

        reserve(scoped.sessionId());
        running.put(executionId, execution);
        events.publish(new CommandEvents.CommandStarted(executionId, id, scoped.sessionId(), scoped.workspaceId()));
        try {
            workers.execute(() -> run(registration, execution, ctx));
        } catch (RuntimeException e) {
            running.remove(executionId);
            release(scoped.sessionId());
            throw e;
        }
        return execution;
    }

    /** Cancels a running execution. Returns false if it already finished or never existed. */
    public boolean cancel(String executionId, dev.forge.core.Ids.SessionId requester) {
        CommandExecution execution = running.get(executionId);
        if (execution == null) {
            return false;
        }
        if (requester == null || execution.sessionId() == null || !requester.equals(execution.sessionId())) {
            throw ForgeException.forbidden("Execution does not belong to this session");
        }
        execution.cancel();
        return true;
    }

    public Optional<CommandExecution> execution(String executionId) {
        return Optional.ofNullable(running.get(executionId));
    }

    private void check(CommandDescriptor descriptor, CommandContext ctx) {
        if (descriptor.requiresSession() && !ctx.request().isAuthenticated()) {
            throw ForgeException.unauthorized("Command requires an authenticated session: " + descriptor.id());
        }
        if (descriptor.requiresWorkspace() && ctx.workspaceId() == null) {
            throw ForgeException.invalidArgument("Command requires a workspace: " + descriptor.id());
        }
        authorizer.authorize(descriptor, ctx);
        for (var argument : descriptor.arguments()) {
            if (!ctx.args().values().containsKey(argument.name()))
                throw ForgeException.invalidArgument("Missing argument: " + argument.name());
            Object value = ctx.args().raw(argument.name());
            boolean valid = switch (argument.type()) {
                case "string" -> value instanceof String;
                case "number" -> value instanceof Number;
                case "object" -> value instanceof java.util.Map;
                case "array" -> value instanceof java.util.List;
                case "boolean" -> value instanceof Boolean;
                case "json" -> true;
                default -> false;
            };
            if (!valid) throw ForgeException.invalidArgument("Invalid argument: " + argument.name());
        }
        descriptor.availability().unavailableReason(ctx).ifPresent(reason -> {
            throw ForgeException.unavailable(reason).with("commandId", descriptor.id().value());
        });
    }

    private void run(CommandRegistry.Registration registration, CommandExecution execution, CommandContext ctx) {
        Thread worker = Thread.currentThread();
        ctx.cancellation().onCancel(worker::interrupt);
        execution.markRunning();
        Instant started = Instant.now();
        CommandDescriptor descriptor = registration.descriptor();
        Log scoped = log.with(ctx.request()).with("commandId", ctx.commandId()).with("executionId", execution.id());
        try {
            ctx.cancellation().throwIfCancelled();
            check(descriptor, ctx);
            Object value = invoke(registration.handler(), ctx, descriptor);
            if (value instanceof CompletionStage<?> stage) {
                stage.whenComplete((result, failure) -> {
                    if (failure != null) {
                        finishFailed(execution, ctx, ForgeException.normalize(failure), scoped, started);
                    } else {
                        finishOk(execution, ctx, descriptor, result, scoped, started);
                    }
                });
            } else {
                finishOk(execution, ctx, descriptor, value, scoped, started);
            }
        } catch (Throwable t) {
            finishFailed(execution, ctx, ForgeException.normalize(t), scoped, started);
        }
    }

    /** Applies the interceptor chain around the registered handler, outermost first. */
    private Object invoke(CommandHandler handler, CommandContext ctx, CommandDescriptor descriptor) throws Exception {
        // Sensitive commands can carry credentials in their arguments (auth.login) or return
        // privileged material. Third-party interceptors never receive those contexts.
        if (descriptor.sensitive()) {
            return handler.execute(ctx);
        }
        List<CommandInterceptor> chain = registry.interceptors();
        CommandHandler composed = handler;
        for (int i = chain.size() - 1; i >= 0; i--) {
            CommandInterceptor interceptor = chain.get(i);
            CommandHandler next = composed;
            composed = context -> interceptor.intercept(context, next);
        }
        return composed.execute(ctx);
    }

    private void finishOk(CommandExecution execution, CommandContext ctx, CommandDescriptor descriptor,
                          Object value, Log scoped, Instant started) {
        execution.complete(value);
        running.remove(execution.id());
        release(execution.sessionId());
        scoped.with("durationMs", Duration.between(started, Instant.now()).toMillis()).debug("Command completed");
        // A sensitive command's result may contain a credential (auth.login issues a token). The
        // direct caller still receives it; the broadcast copy carries only the outcome.
        Object announced = descriptor.sensitive() ? null : value;
        events.publish(new CommandEvents.CommandCompleted(execution.id(), ctx.commandId(), announced,
                ctx.sessionId(), ctx.workspaceId()));
    }

    private void finishFailed(CommandExecution execution, CommandContext ctx, ForgeException failure,
                              Log scoped, Instant started) {
        execution.fail(failure);
        running.remove(execution.id());
        release(execution.sessionId());
        if (failure.code() == ForgeException.Code.INTERNAL_FAILURE) {
            scoped.error("Command failed", failure);
        } else {
            scoped.with("code", failure.code())
                    .with("durationMs", Duration.between(started, Instant.now()).toMillis())
                    .debug("Command rejected: " + failure.getMessage());
        }
        events.publish(new CommandEvents.CommandFailed(execution.id(), ctx.commandId(), failure.code().name(),
                failure.code() == ForgeException.Code.INTERNAL_FAILURE ? "An internal error occurred" : failure.getMessage(), ctx.sessionId(), ctx.workspaceId()));
    }

    private CommandExecution failFast(CommandExecution execution, CommandContext ctx, ForgeException failure) {
        execution.fail(failure);
        events.publish(new CommandEvents.CommandFailed(execution.id(), ctx.commandId(), failure.code().name(),
                failure.code() == ForgeException.Code.INTERNAL_FAILURE ? "An internal error occurred" : failure.getMessage(), ctx.sessionId(), ctx.workspaceId()));
        return execution;
    }
    private synchronized void reserve(dev.forge.core.Ids.SessionId session) {
        int global = runningCount.incrementAndGet();
        if (global > maxRunning) {
            runningCount.decrementAndGet();
            throw ForgeException.unavailable("Too many commands are running");
        }
        if (session == null) {
            return;
        }
        AtomicInteger count = runningBySession.computeIfAbsent(session, ignored -> new AtomicInteger());
        if (count.incrementAndGet() > maxRunningPerSession) {
            if (count.decrementAndGet() == 0) {
                runningBySession.remove(session, count);
            }
            runningCount.decrementAndGet();
            throw ForgeException.unavailable("Too many commands are running for this session");
        }
    }

    private synchronized void release(dev.forge.core.Ids.SessionId session) {
        runningCount.updateAndGet(value -> Math.max(0, value - 1));
        if (session == null) {
            return;
        }
        AtomicInteger count = runningBySession.get(session);
        if (count != null && count.decrementAndGet() <= 0) {
            runningBySession.remove(session, count);
        }
    }

}
