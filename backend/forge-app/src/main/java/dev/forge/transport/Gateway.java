package dev.forge.transport;

import dev.forge.auth.Session;
import dev.forge.auth.SessionService;
import dev.forge.core.Args;
import dev.forge.core.Cancellation;
import dev.forge.core.ForgeException;
import dev.forge.core.Ids.WorkspaceId;
import dev.forge.core.Log;
import dev.forge.core.Lifecycle;
import dev.forge.core.RequestContext;
import dev.forge.core.command.CommandExecution;
import dev.forge.core.command.CommandExecutor;
import dev.forge.core.command.CommandId;
import dev.forge.core.query.QueryRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;

/**
 * The transport gateway: the single door from the outside world into the application.
 *
 * <p>Everything external — browser, desktop shell, CLI, an automation client, a future agent —
 * arrives here and is reduced to the same three things: run a command, run a query, subscribe to
 * events. There is no controller per feature and no HTTP endpoint that reaches into a service,
 * so application logic never learns who called it.
 *
 * <p>This class is also the only place that turns a bearer token into a
 * {@link RequestContext}. Nothing downstream reads a header.
 */
public final class Gateway implements Lifecycle.Component {

    private static final Log log = Log.of(Gateway.class);

    /**
     * A uniform result. {@code pending} means a long-running command is still going: the caller
     * keeps the {@code executionId} and watches for {@code command.completed} on the event stream.
     */
    public record Result(boolean ok, Object value, ErrorView error, String executionId, boolean pending) {

        static Result value(Object value, String executionId) {
            return new Result(true, value, null, executionId, false);
        }

        static Result pending(String executionId) {
            return new Result(true, null, null, executionId, true);
        }

        static Result failure(ForgeException failure, String executionId) {
            return new Result(false, null, ErrorView.of(failure), executionId, false);
        }
    }

    /** The client-facing shape of a failure. Never contains a stack trace. */
    public record ErrorView(String code, String message, Map<String, String> details) {
        static ErrorView of(ForgeException failure) {
            String message = failure.code() == ForgeException.Code.INTERNAL_FAILURE
                    ? "An internal error occurred"
                    : failure.getMessage();
            return new ErrorView(failure.code().name(), message, failure.details());
        }
    }

    private record Outcome(dev.forge.core.Ids.SessionId owner, long expires, String json) { }
    private final java.util.LinkedHashMap<String, Outcome> outcomes = new java.util.LinkedHashMap<>();
    private final Json codec = new Json();
    private int outcomeChars;

    private synchronized void retain(String id, dev.forge.core.Ids.SessionId owner, Result result) {
        if (owner == null) return;
        String encoded = codec.write(result);
        if (encoded.length() > 2 * 1024 * 1024) encoded = codec.write(Result.failure(
                ForgeException.unavailable("Outcome too large to retain; refresh authoritative state"), id));
        while (!outcomes.isEmpty() && (outcomes.size() >= 128 || outcomeChars + encoded.length() > 8 * 1024 * 1024
                || outcomes.firstEntry().getValue().expires() < System.nanoTime())) {
            outcomeChars -= outcomes.pollFirstEntry().getValue().json().length();
        }
        outcomes.put(id, new Outcome(owner, System.nanoTime() + 120_000_000_000L, encoded));
        outcomeChars += encoded.length();
    }

    private synchronized Object outcome(String id, RequestContext ctx) {
        if (!ctx.isAuthenticated()) throw ForgeException.unauthorized("Authentication required");
        Outcome saved = outcomes.get(id);
        if (saved != null && saved.owner().equals(ctx.sessionId()) && saved.expires() >= System.nanoTime())
            return codec.readObject(new java.io.ByteArrayInputStream(saved.json().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var running = commands.execution(id).filter(value -> ctx.sessionId().equals(value.sessionId()));
        if (running.isPresent()) return Result.pending(id);
        throw ForgeException.notFound("Command outcome expired or is unavailable; refresh authoritative state");
    }

    private final CommandExecutor commands;
    private final QueryRegistry queries;
    private final SessionService sessions;
    private final Duration synchronousTimeout;
    private final Duration queryTimeout;
    private final java.util.concurrent.Semaphore querySlots = new java.util.concurrent.Semaphore(32);
    private final ExecutorService queryWorkers = Executors.newVirtualThreadPerTaskExecutor();

    public Gateway(CommandExecutor commands, QueryRegistry queries, SessionService sessions,
                   Duration synchronousTimeout, Duration queryTimeout) {
        this.commands = commands;
        this.queries = queries;
        this.sessions = sessions;
        this.synchronousTimeout = synchronousTimeout;
        this.queryTimeout = queryTimeout;
    }

    @Override
    public void start() {
        // Executor is ready at construction time.
    }

    @Override
    public void dispose() {
        queryWorkers.shutdownNow();
    }

    /**
     * Resolves a caller. An absent or invalid token yields an anonymous context rather than an
     * error, because a few commands ({@code auth.login}) legitimately run without a session;
     * the executor refuses everything else.
     */
    public RequestContext contextFor(String bearerToken, String workspaceHeader, Cancellation cancellation) {
        Optional<Session> session = sessions.authenticate(bearerToken);
        WorkspaceId workspace = workspaceHeader == null || workspaceHeader.isBlank()
                ? null
                : WorkspaceId.of(workspaceHeader);
        return session
                .map(active -> new RequestContext(active.userId(), active.id(), workspace,
                        RequestContext.Origin.UI, cancellation.token()))
                .orElseGet(() -> new RequestContext(null, null, workspace,
                        RequestContext.Origin.UI, cancellation.token()));
    }

    public Result command(String id, Args args, boolean async, RequestContext ctx) {
        if (async && "auth.login".equals(id)) return Result.failure(ForgeException.invalidArgument("Login must be synchronous"), null);
        CommandExecution execution;
        try {
            execution = commands.execute(CommandId.of(id), args, ctx);
        } catch (RuntimeException e) {
            return Result.failure(ForgeException.normalize(e), null);
        }
        if (!"auth.login".equals(id)) execution.result().whenComplete((value, failure) ->
                retain(execution.id(), ctx.sessionId(), failure == null ? Result.value(value, execution.id())
                        : Result.failure(ForgeException.normalize(failure), execution.id())));
        if (async) {
            return Result.pending(execution.id());
        }
        try {
            Object value = execution.result().get(synchronousTimeout.toMillis(), TimeUnit.MILLISECONDS);
            return Result.value(value, execution.id());
        } catch (TimeoutException e) {
            if ("auth.login".equals(id)) { execution.cancel(); return Result.failure(ForgeException.unavailable("Login timed out"), execution.id()); }
            // Not a failure: the command is still running and will announce itself on the
            // event stream. Cancellation stays possible through command.cancel.
            log.with("commandId", id).with("executionId", execution.id()).info("Command still running");
            return Result.pending(execution.id());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.failure(ForgeException.cancelled("Request interrupted"), execution.id());
        } catch (java.util.concurrent.ExecutionException e) {
            return Result.failure(ForgeException.normalize(e), execution.id());
        }
    }

    public Result query(String id, Args args, RequestContext ctx) {
        if ("command.result".equals(id)) {
            try { return Result.value(outcome(args.requiredString("executionId"), ctx), null); }
            catch (RuntimeException e) { return Result.failure(ForgeException.normalize(e), null); }
        }
        if (!querySlots.tryAcquire()) return Result.failure(ForgeException.unavailable("Too many queries"), null);
        java.util.concurrent.Future<Object> future;
        try {
            var task = new java.util.concurrent.FutureTask<Object>(() -> queries.execute(id, args, ctx)) {
                @Override public void run() { try { super.run(); } finally { querySlots.release(); } }
            };
            future = task;
            queryWorkers.execute(task);
        } catch (RuntimeException e) {
            querySlots.release();
            return Result.failure(ForgeException.unavailable("Queries are unavailable"), null);
        }
        try {
            return Result.value(future.get(queryTimeout.toMillis(), TimeUnit.MILLISECONDS), null);
        } catch (TimeoutException e) {
            future.cancel(true);
            return Result.failure(ForgeException.unavailable("Query timed out"), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return Result.failure(ForgeException.cancelled("Query interrupted"), null);
        } catch (java.util.concurrent.ExecutionException e) {
            ForgeException failure = ForgeException.normalize(e);
            if (failure.code() == ForgeException.Code.INTERNAL_FAILURE) {
                log.with("queryId", id).error("Query failed", failure);
            }
            return Result.failure(failure, null);
        } catch (RuntimeException e) {
            ForgeException failure = ForgeException.normalize(e);
            return Result.failure(failure, null);
        }
    }
}
