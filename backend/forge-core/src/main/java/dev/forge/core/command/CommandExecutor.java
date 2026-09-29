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













public final class CommandExecutor implements dev.forge.core.Component {

    private static final Log log = Log.of(CommandExecutor.class);







    private final CommandRegistry registry;
    private final EventBus events;
    private final Authorizer authorizer;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, CommandExecution> running = new ConcurrentHashMap<>();
    private final AtomicInteger runningCount = new AtomicInteger();
    private final Map<dev.forge.core.SessionId, AtomicInteger> runningBySession = new ConcurrentHashMap<>();
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





    public CommandExecution execute(CommandId id, Args args, RequestContext request) {
        if (!request.isAuthenticated() && request.origin() != dev.forge.core.Origin.SYSTEM && !"auth.login".equals(id.value()))
            throw ForgeException.unauthorized("Authentication required");
        Cancellation cancellation = new Cancellation();
        request.cancellation().onCancel(cancellation::cancel);

        String executionId = Ids.random("exec");
        CommandExecution execution = new CommandExecution(executionId, id, cancellation, request.sessionId());
        RequestContext scoped = new RequestContext(request.userId(), request.sessionId(),
                request.workspaceId(), request.origin(), cancellation.token());
        CommandContext ctx = new CommandContext(id, args == null ? Args.EMPTY : args, scoped, executionId);

        dev.forge.core.command.Registration registration;
        try {
            registration = registry.find(id)
                    .orElseThrow(() -> ForgeException.notFound("Unknown command: " + id).with("commandId", id.value()));
            check(registration.descriptor(), ctx);
        } catch (RuntimeException e) {
            return failFast(execution, ctx, ForgeException.normalize(e));
        }

        reserve(scoped.sessionId());
        running.put(executionId, execution);
        events.publish(new dev.forge.core.command.CommandStarted(executionId, id, scoped.sessionId(), scoped.workspaceId()));
        try {
            workers.execute(() -> run(registration, execution, ctx));
        } catch (RuntimeException e) {
            running.remove(executionId);
            release(scoped.sessionId());
            throw e;
        }
        return execution;
    }


    public boolean cancel(String executionId, dev.forge.core.SessionId requester) {
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

    private void run(dev.forge.core.command.Registration registration, CommandExecution execution, CommandContext ctx) {
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


    private Object invoke(CommandHandler handler, CommandContext ctx, CommandDescriptor descriptor) throws Exception {
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


        Object announced = descriptor.sensitive() ? null : value;
        events.publish(new dev.forge.core.command.CommandCompleted(execution.id(), ctx.commandId(), announced,
                ctx.sessionId(), ctx.workspaceId()));
    }

    private void finishFailed(CommandExecution execution, CommandContext ctx, ForgeException failure,
                              Log scoped, Instant started) {
        execution.fail(failure);
        running.remove(execution.id());
        release(execution.sessionId());
        if (failure.code() == dev.forge.core.Code.INTERNAL_FAILURE) {
            scoped.error("Command failed", failure);
        } else {
            scoped.with("code", failure.code())
                    .with("durationMs", Duration.between(started, Instant.now()).toMillis())
                    .debug("Command rejected: " + failure.getMessage());
        }
        events.publish(new dev.forge.core.command.CommandFailed(execution.id(), ctx.commandId(), failure.code().name(),
                failure.code() == dev.forge.core.Code.INTERNAL_FAILURE ? "An internal error occurred" : failure.getMessage(), ctx.sessionId(), ctx.workspaceId()));
    }

    private CommandExecution failFast(CommandExecution execution, CommandContext ctx, ForgeException failure) {
        execution.fail(failure);
        events.publish(new dev.forge.core.command.CommandFailed(execution.id(), ctx.commandId(), failure.code().name(),
                failure.code() == dev.forge.core.Code.INTERNAL_FAILURE ? "An internal error occurred" : failure.getMessage(), ctx.sessionId(), ctx.workspaceId()));
        return execution;
    }
    private synchronized void reserve(dev.forge.core.SessionId session) {
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

    private synchronized void release(dev.forge.core.SessionId session) {
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
