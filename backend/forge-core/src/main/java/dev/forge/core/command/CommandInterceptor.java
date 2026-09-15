package dev.forge.core.command;

/**
 * Wraps command execution without replacing it.
 *
 * <p>This is how a product or an extension adds cross-cutting behaviour — audit trails, extra
 * confirmation, metrics, a policy check — to commands it does not own. Interceptors run in
 * ascending {@link #order()} around the registered handler.
 *
 * <p>Interception is intentionally distinct from replacement: an interceptor can observe and
 * decorate, but the original handler stays in the registry and stays discoverable.
 */
public interface CommandInterceptor {

    Object intercept(CommandContext ctx, CommandHandler next) throws Exception;

    default int order() {
        return 0;
    }
}
