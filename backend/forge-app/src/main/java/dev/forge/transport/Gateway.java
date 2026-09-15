package dev.forge.transport;

import dev.forge.auth.Session;
import dev.forge.auth.SessionService;
import dev.forge.core.Args;
import dev.forge.core.Cancellation;
import dev.forge.core.ForgeException;
import dev.forge.core.Ids.WorkspaceId;
import dev.forge.core.Log;
import dev.forge.core.RequestContext;
import dev.forge.core.command.CommandExecution;
import dev.forge.core.command.CommandExecutor;
import dev.forge.core.command.CommandId;
import dev.forge.core.query.QueryRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
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
public final class Gateway {

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

    private final CommandExecutor commands;
    private final QueryRegistry queries;
    private final SessionService sessions;
    private final Duration synchronousTimeout;

    public Gateway(CommandExecutor commands, QueryRegistry queries, SessionService sessions,
                   Duration synchronousTimeout) {
        this.commands = commands;
        this.queries = queries;
        this.sessions = sessions;
        this.synchronousTimeout = synchronousTimeout;
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
        CommandExecution execution;
        try {
            execution = commands.execute(CommandId.of(id), args, ctx);
        } catch (RuntimeException e) {
            return Result.failure(ForgeException.normalize(e), null);
        }
        if (async) {
            return Result.pending(execution.id());
        }
        try {
            Object value = execution.result().get(synchronousTimeout.toMillis(), TimeUnit.MILLISECONDS);
            return Result.value(value, execution.id());
        } catch (TimeoutException e) {
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
        try {
            return Result.value(queries.execute(id, args, ctx), null);
        } catch (RuntimeException e) {
            ForgeException failure = ForgeException.normalize(e);
            if (failure.code() == ForgeException.Code.INTERNAL_FAILURE) {
                log.with("queryId", id).error("Query failed", failure);
            }
            return Result.failure(failure, null);
        }
    }
}
