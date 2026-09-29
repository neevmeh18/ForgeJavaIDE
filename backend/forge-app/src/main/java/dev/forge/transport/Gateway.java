package dev.forge.transport;

import dev.forge.auth.Session;
import dev.forge.auth.SessionService;
import dev.forge.core.Args;
import dev.forge.core.Cancellation;
import dev.forge.core.ForgeException;
import dev.forge.core.WorkspaceId;
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












public final class Gateway implements dev.forge.core.Component {

    private static final Log log = Log.of(Gateway.class);










    private record Outcome(dev.forge.core.SessionId owner, long expires, String json) { }
    private final java.util.LinkedHashMap<String, Outcome> outcomes = new java.util.LinkedHashMap<>();
    private final Json codec = new Json();
    private int outcomeChars;

    private synchronized void retain(String id, dev.forge.core.SessionId owner, Result result) {
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

    }

    @Override
    public void dispose() {
        queryWorkers.shutdownNow();
    }






    public RequestContext contextFor(String bearerToken, String workspaceHeader, Cancellation cancellation) {
        Optional<Session> session = sessions.authenticate(bearerToken);
        WorkspaceId workspace = workspaceHeader == null || workspaceHeader.isBlank()
                ? null
                : WorkspaceId.of(workspaceHeader);
        return session
                .map(active -> new RequestContext(active.userId(), active.id(), workspace,
                        dev.forge.core.Origin.UI, cancellation.token()))
                .orElseGet(() -> new RequestContext(null, null, workspace,
                        dev.forge.core.Origin.UI, cancellation.token()));
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
            if (failure.code() == dev.forge.core.Code.INTERNAL_FAILURE) {
                log.with("queryId", id).error("Query failed", failure);
            }
            return Result.failure(failure, null);
        } catch (RuntimeException e) {
            ForgeException failure = ForgeException.normalize(e);
            return Result.failure(failure, null);
        }
    }
}
