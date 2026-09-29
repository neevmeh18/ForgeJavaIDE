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
