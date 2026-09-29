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

public record ErrorView(String code, String message, Map<String, String> details) {
    static ErrorView of(ForgeException failure) {
        String message = failure.code() == dev.forge.core.Code.INTERNAL_FAILURE
                ? "An internal error occurred"
                : failure.getMessage();
        return new ErrorView(failure.code().name(), message, failure.details());
    }
}
