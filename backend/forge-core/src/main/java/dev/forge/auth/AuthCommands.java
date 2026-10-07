package dev.forge.auth;

import dev.forge.core.ForgeException;
import dev.forge.core.Ids.SessionId;
import dev.forge.core.command.CommandDescriptor;
import dev.forge.core.command.CommandRegistry;
import dev.forge.core.query.QueryRegistry;
import dev.forge.core.query.QueryRegistry.QueryDescriptor;
import java.time.Instant;
import java.util.Optional;

/**
 * {@code auth.*} commands and queries.
 *
 * <p>{@code auth.login} is the one command that may run without a session, and it is marked
 * sensitive so its result — which contains a freshly issued token — is never broadcast on the
 * event bus and no extension can replace it.
 */
public final class AuthCommands {

    /** What a client receives on successful login. The token is shown exactly once. */
    public record LoginResult(String token, SessionId sessionId, User user, Instant expiresAt) {
    }

    /** What a client receives on successful registration (same shape — token, session, user). */
    public record RegisterResult(String token, SessionId sessionId, User user, Instant expiresAt) {
    }

    public record CurrentUser(User user, SessionId sessionId, Instant expiresAt) {
    }

    private final AuthenticationProvider provider;
    private final SessionService sessions;

    public AuthCommands(AuthenticationProvider provider, SessionService sessions) {
        this.provider = provider;
        this.sessions = sessions;
    }

    public void register(CommandRegistry commands, QueryRegistry queries) {
        commands.register(
                CommandDescriptor.of("auth.login", "Authentication", "Sign In") .withArguments(new CommandDescriptor.Argument("username", "string", "username"), new CommandDescriptor.Argument("password", "string", "password"))
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
                    AuthenticationProvider.Credentials credentials =
                            new AuthenticationProvider.Credentials(username, secret, java.util.Map.of());
                    try {
                        User user = provider.authenticate(credentials)
                                .orElseThrow(() -> ForgeException.unauthorized("Invalid username or password"));
                        SessionService.Issued issued =
                                sessions.issue(user, client);
                        return new LoginResult(issued.token(), issued.session().id(), user, issued.expiresAt());
                    } finally {
                        credentials.wipe();
                    }
                });

        commands.register(
                CommandDescriptor.of("auth.register", "Authentication", "Create Account")
                        .describedAs("Creates a new user account and returns a session token")
                        .withArguments(
                                new CommandDescriptor.Argument("username", "string", "login name"),
                                new CommandDescriptor.Argument("password", "string", "password"),
                                new CommandDescriptor.Argument("displayName", "string", "display name (optional)"))
                        .anonymous().asSensitive(),
                ctx -> {
                    if (!(provider instanceof AuthenticationProvider.Registrar registrar)) {
                        throw ForgeException.unsupported("Registration is not available on this server");
                    }
                    String username = ctx.args().requiredString("username");
                    String password = ctx.args().requiredString("password");
                    if (username.length() < 2 || username.length() > 128) {
                        throw ForgeException.invalidArgument("Username must be between 2 and 128 characters");
                    }
                    if (password.length() < 6) {
                        throw ForgeException.invalidArgument("Password must be at least 6 characters");
                    }
                    if (password.length() > 1024) {
                        throw ForgeException.invalidArgument("Password exceeds the supported length");
                    }
                    String displayName = ctx.args().string("displayName").orElse(username);
                    String client = ctx.args().string("client").orElse("");
                    if (client.length() > 512) {
                        throw ForgeException.invalidArgument("Client description is too long");
                    }
                    char[] secret = password.toCharArray();
                    try {
                        User user = registrar.register(username, secret, displayName, java.util.Map.of());
                        SessionService.Issued issued = sessions.issue(user, client);
                        return new RegisterResult(issued.token(), issued.session().id(), user, issued.expiresAt());
                    } finally {
                        java.util.Arrays.fill(secret, '\0');
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
