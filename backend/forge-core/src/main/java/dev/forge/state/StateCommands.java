package dev.forge.state;

import dev.forge.core.ForgeException;
import dev.forge.core.RequestContext;
import dev.forge.core.command.CommandDescriptor;
import dev.forge.core.command.CommandRegistry;
import dev.forge.core.query.QueryRegistry;
import dev.forge.core.query.QueryDescriptor;
import java.util.Locale;
import java.util.regex.Pattern;








public final class StateCommands {

    private static final Pattern VALID_KEY = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}");

    private final StateStore store;

    public StateCommands(StateStore store) {
        this.store = store;
    }

    public void register(CommandRegistry commands, QueryRegistry queries) {
        commands.register(
                CommandDescriptor.of("state.set", "State", "Set State Value") .withArguments(new dev.forge.core.command.Argument("scope", "string", "scope"), new dev.forge.core.command.Argument("key", "string", "key"), new dev.forge.core.command.Argument("value", "json", "value")).asSensitive(),
                ctx -> {
                    dev.forge.state.Scope scope = scope(ctx.args().requiredString("scope"));
                    store.put(scope, owner(scope, ctx.request()), key(ctx.args().requiredString("key")),
                            ctx.args().raw("value"));
                    return null;
                });

        queries.register(
                QueryDescriptor.of("state.get", "Stored values for a scope"),
                (ctx, args) -> {
                    dev.forge.state.Scope scope = scope(args.requiredString("scope"));
                    return store.read(scope, owner(scope, ctx));
                });
    }

    private static dev.forge.state.Scope scope(String raw) {
        try {
            return dev.forge.state.Scope.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ForgeException.invalidArgument("Unknown state scope: " + raw);
        }
    }

    private static String key(String raw) {
        if (!VALID_KEY.matcher(raw).matches()) {
            throw ForgeException.invalidArgument("Invalid state key: " + raw);
        }
        return raw;
    }

    private static String owner(dev.forge.state.Scope scope, RequestContext ctx) {
        return switch (scope) {
            case APPLICATION -> "";
            case USER -> ctx.requireUser().value();
            case WORKSPACE -> ctx.requireWorkspace().value();
            case SESSION -> {
                if (ctx.sessionId() == null) {
                    throw ForgeException.unauthorized("Session state requires a session");
                }
                yield ctx.sessionId().value();
            }
        };
    }
}
