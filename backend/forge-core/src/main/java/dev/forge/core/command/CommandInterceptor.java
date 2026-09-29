package dev.forge.core.command;











public interface CommandInterceptor {

    Object intercept(CommandContext ctx, CommandHandler next) throws Exception;

    default int order() {
        return 0;
    }
}
