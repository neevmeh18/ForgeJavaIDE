package dev.forge.core.command;

import java.util.function.Consumer;












@FunctionalInterface
public interface CommandHandler {

    Object execute(CommandContext ctx) throws Exception;


    static CommandHandler action(Consumer<CommandContext> action) {
        return ctx -> {
            action.accept(ctx);
            return null;
        };
    }
}
