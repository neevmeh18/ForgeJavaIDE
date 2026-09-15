package dev.forge.core.command;

import java.util.function.Consumer;

/**
 * The work behind a command.
 *
 * <p>A single functional interface, so the common case stays a one-liner:
 * {@code commands.register(descriptor, fileService::save)}. Dedicated handler classes are for
 * commands that genuinely orchestrate several features — not for every action.
 *
 * <p>A handler may return a value, {@code null}, or a {@link java.util.concurrent.CompletionStage}
 * for work that completes later; {@link CommandExecutor} unwraps the stage so callers see one
 * consistent asynchronous result either way.
 */
@FunctionalInterface
public interface CommandHandler {

    Object execute(CommandContext ctx) throws Exception;

    /** Adapts a handler that has no meaningful result. */
    static CommandHandler action(Consumer<CommandContext> action) {
        return ctx -> {
            action.accept(ctx);
            return null;
        };
    }
}
