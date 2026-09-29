package dev.forge.auth;

import dev.forge.core.ForgeException;
import dev.forge.core.SessionId;
import dev.forge.core.command.CommandDescriptor;
import dev.forge.core.command.CommandRegistry;
import dev.forge.core.query.QueryRegistry;
import dev.forge.core.query.QueryDescriptor;
import java.time.Instant;
import java.util.Optional;








public final class AuthCommands {






    private final AuthenticationProvider provider;
    private final SessionService sessions;

    public AuthCommands(AuthenticationProvider provider, SessionService sessions) {
        this.provider = provider;
        this.sessions = sessions;
    }

    public void register(CommandRegistry commands, QueryRegistry queries) {
        commands.register(
                CommandDescriptor.of("auth.login", "Authentication", "Sign In") .withArguments(new dev.forge.core.command.Argument("username", "string", "username"), new dev.forge.core.command.Argument("password", "string", "password"))
                        .describedAs("Exchanges credentials for a session token")
                        .anonymous().asSensitive(),
                ctx -> {
                    String username = ctx.args().requiredString("username");
                    String password = ctx.args().requiredString("password");
                    if (username.length() > 128 || password.length() > 1024) {
                        throw ForgeException.invalidArgument("Credentials exceed the supported length");
                    }
                    String client = ctx.args().string("client").orElse("");
                    if (client.length() > 512) {
                        throw ForgeException.invalidArgument("Client description is too long");
                    }
                    char[] secret = password.toCharArray();
                    dev.forge.auth.Credentials credentials =
                            new dev.forge.auth.Credentials(username, secret, java.util.Map.of());
                    try {
                        User user = provider.authenticate(credentials)
                                .orElseThrow(() -> ForgeException.unauthorized("Invalid username or password"));
                        dev.forge.auth.Issued issued =
                                sessions.issue(user, client);
                        return new LoginResult(issued.token(), issued.session().id(), user, issued.expiresAt());
                    } finally {
                        credentials.wipe();
                    }
                });

        commands.register(
                CommandDescriptor.of("auth.logout", "Authentication", "Sign Out").asSensitive(),
                ctx -> {
                    sessions.revoke(ctx.sessionId(), "logout");
                    return null;
                });

        queries.register(
                QueryDescriptor.of("auth.currentUser", "The identity behind this request"),
                (ctx, args) -> {
                    Optional<Session> session = sessions.find(ctx.sessionId());
                    return session
                            .map(s -> new CurrentUser(
                                    new User(s.userId(), s.userId().value(), provider.id(), java.util.Map.of()),
                                    s.id(), s.expiresAt()))
                            .orElseThrow(() -> ForgeException.unauthorized("No active session"));
                });
    }
}
