package dev.forge.auth;

import dev.forge.core.ForgeException;
import dev.forge.core.Ids.SessionId;
import dev.forge.core.Ids.UserId;
import dev.forge.core.command.CommandDescriptor;
import dev.forge.core.command.CommandRegistry;
import dev.forge.core.query.QueryRegistry;
import dev.forge.core.query.QueryRegistry.QueryDescriptor;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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

    public record CurrentUser(User user, SessionId sessionId, Instant expiresAt) {
    }

    /** A view-safe representation of a user. */
    public record UserView(String id, String username, String role, boolean enabled, String createdAt) {
        static UserView of(StoredUser u) {
            return new UserView(u.id().value(), u.username(), u.role(), u.enabled(),
                    u.createdAt().toString());
        }
    }

    /** A view-safe representation of a role with its permissions. */
    public record RoleView(String name, String displayName, List<String> permissions) {
    }

    /** The result of a permissions query. */
    public record PermsResult(String role, List<String> permissions) {
    }

    private static final int MIN_PASSWORD_LENGTH = 12;

    private final AuthenticationProvider provider;
    private final SessionService sessions;
    private final UserStore users;
    private final RoleService roles;
    private final AuditService audit;

    public AuthCommands(AuthenticationProvider provider, SessionService sessions,
                        UserStore users, RoleService roles, AuditService audit) {
        this.provider = provider;
        this.sessions = sessions;
        this.users = users;
        this.roles = roles;
        this.audit = audit;
    }

    public void register(CommandRegistry commands, QueryRegistry queries) {
        // ---- auth.login ---------------------------------------------------------------
        commands.register(
                CommandDescriptor.of("auth.login", "Authentication", "Sign In")
                        .withArguments(
                                new CommandDescriptor.Argument("username", "string", "username"),
                                new CommandDescriptor.Argument("password", "string", "password"))
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
                            new AuthenticationProvider.Credentials(username, secret, Map.of());
                    try {
                        User user = provider.authenticate(credentials)
                                .orElseThrow(() -> ForgeException.unauthorized("Invalid username or password"));

                        // Token rotation: revoke any existing sessions for this user
                        for (Session existing : sessions.findByUser(user.id())) {
                            sessions.revoke(existing.id(), "login");
                        }

                        SessionService.Issued issued = sessions.issue(user, client);
                        return new LoginResult(issued.token(), issued.session().id(), user, issued.expiresAt());
                    } finally {
                        credentials.wipe();
                    }
                });

        // ---- auth.logout --------------------------------------------------------------
        commands.register(
                CommandDescriptor.of("auth.logout", "Authentication", "Sign Out").asSensitive(),
                ctx -> {
                    sessions.revoke(ctx.sessionId(), "logout");
                    audit.record(AuditEntry.ofSuccess(
                            ctx.request().userId(), ctx.sessionId(), "auth.logout", "", ""));
                    return null;
                });

        // ---- auth.currentUser (query) ------------------------------------------------
        queries.register(
                QueryDescriptor.of("auth.currentUser", "The identity behind this request"),
                (ctx, args) -> {
                    Optional<Session> session = sessions.find(ctx.sessionId());
                    return session
                            .map(s -> new CurrentUser(
                                    new User(s.userId(), s.userId().value(), provider.id(),
                                            Map.of("role", s.role())),
                                    s.id(), s.expiresAt()))
                            .orElseThrow(() -> ForgeException.unauthorized("No active session"));
                });

        // ---- whoami (query) ----------------------------------------------------------
        queries.register(
                QueryDescriptor.of("whoami", "Current user identity and role"),
                (ctx, args) -> {
                    Optional<Session> session = sessions.find(ctx.sessionId());
                    return session
                            .map(s -> Map.of(
                                    "userId", s.userId().value(),
                                    "sessionId", s.id().value(),
                                    "role", s.role(),
                                    "createdAt", s.createdAt().toString(),
                                    "expiresAt", s.expiresAt().toString()))
                            .orElseThrow(() -> ForgeException.unauthorized("No active session"));
                });

        // ---- users (query) -----------------------------------------------------------
        queries.register(
                QueryDescriptor.of("auth.users", "List all registered users"),
                (ctx, args) -> users.list().stream().map(UserView::of).toList());

        // ---- roles (query) -----------------------------------------------------------
        queries.register(
                QueryDescriptor.of("auth.roles", "List all roles and their permissions"),
                (ctx, args) -> roles.listRoles().stream()
                        .map(r -> new RoleView(r.name(), r.displayName(),
                                List.copyOf(r.permissions())))
                        .toList());

        // ---- perms (query) -----------------------------------------------------------
        queries.register(
                QueryDescriptor.of("auth.perms", "List permissions for the current role"),
                (ctx, args) -> {
                    String roleName = sessions.roleOf(ctx.sessionId()).orElse("guest");
                    Role role = roles.role(roleName);
                    return new PermsResult(role.name(), List.copyOf(role.permissions()));
                });

        // ---- passwd (command) --------------------------------------------------------
        commands.register(
                CommandDescriptor.of("auth.passwd", "Authentication", "Change Password")
                        .withArguments(
                                new CommandDescriptor.Argument("oldPassword", "string", "current password"),
                                new CommandDescriptor.Argument("newPassword", "string", "new password"))
                        .describedAs("Changes the authenticated user's password"),
                ctx -> {
                    String oldPassword = ctx.args().requiredString("oldPassword");
                    String newPassword = ctx.args().requiredString("newPassword");
                    if (newPassword.length() < MIN_PASSWORD_LENGTH) {
                        throw ForgeException.invalidArgument(
                                "Password must be at least " + MIN_PASSWORD_LENGTH + " characters");
                    }
                    if (newPassword.length() > 1024) {
                        throw ForgeException.invalidArgument("Password exceeds the supported length");
                    }

                    // Re-authenticate the user
                    char[] oldSecret = oldPassword.toCharArray();
                    AuthenticationProvider.Credentials credentials =
                            new AuthenticationProvider.Credentials(
                                    ctx.sessionId().value(), oldSecret, Map.of());
                    try {
                        provider.authenticate(credentials)
                                .orElseThrow(() -> ForgeException.unauthorized("Current password is incorrect"));
                    } finally {
                        credentials.wipe();
                    }

                    // Find the stored user and update the password
                    Optional<Session> session = sessions.find(ctx.sessionId());
                    if (session.isEmpty()) {
                        throw ForgeException.unauthorized("No active session");
                    }
                    String username = session.get().userId().value();
                    Optional<StoredUser> stored = users.find(username);
                    if (stored.isEmpty()) {
                        throw ForgeException.notFound("User not found");
                    }

                    byte[] salt = PasswordHasher.generateSalt();
                    char[] newSecret = newPassword.toCharArray();
                    try {
                        byte[] hash = PasswordHasher.hash(newSecret, salt);
                        String hashB64 = Base64.getEncoder().encodeToString(hash);
                        String saltB64 = Base64.getEncoder().encodeToString(salt);
                        users.update(stored.get().withPassword(hashB64, saltB64));
                    } finally {
                        java.util.Arrays.fill(newSecret, '\0');
                    }

                    audit.record(AuditEntry.ofSuccess(
                            ctx.request().userId(), ctx.sessionId(), "auth.passwd", "", ""));
                    return null;
                });

        // ---- useradd (command) -------------------------------------------------------
        commands.register(
                CommandDescriptor.of("auth.useradd", "Authentication", "Add User")
                        .withArguments(
                                new CommandDescriptor.Argument("username", "string", "username"),
                                new CommandDescriptor.Argument("password", "string", "password"),
                                new CommandDescriptor.Argument("role", "string", "role (optional)"))
                        .describedAs("Creates a new user account"),
                ctx -> {
                    String username = ctx.args().requiredString("username");
                    String password = ctx.args().requiredString("password");
                    String role = ctx.args().string("role").orElse("developer");

                    if (username.length() < 3) {
                        throw ForgeException.invalidArgument("Username must be at least 3 characters");
                    }
                    if (password.length() < MIN_PASSWORD_LENGTH) {
                        throw ForgeException.invalidArgument(
                                "Password must be at least " + MIN_PASSWORD_LENGTH + " characters");
                    }
                    if (users.exists(username)) {
                        throw ForgeException.conflict("User already exists: " + username);
                    }

                    // Validate the role exists
                    try {
                        roles.role(role);
                    } catch (ForgeException e) {
                        throw ForgeException.invalidArgument("Unknown role: " + role);
                    }

                    byte[] salt = PasswordHasher.generateSalt();
                    char[] secret = password.toCharArray();
                    try {
                        byte[] hash = PasswordHasher.hash(secret, salt);
                        String hashB64 = Base64.getEncoder().encodeToString(hash);
                        String saltB64 = Base64.getEncoder().encodeToString(salt);
                        StoredUser stored = StoredUser.of(
                                UserId.of(username), username, hashB64, saltB64, role);
                        users.create(stored);
                    } finally {
                        java.util.Arrays.fill(secret, '\0');
                    }

                    audit.record(AuditEntry.ofSuccess(
                            ctx.request().userId(), ctx.sessionId(), "auth.useradd", username, ""));
                    return null;
                });

        // ---- userdel (command) -------------------------------------------------------
        commands.register(
                CommandDescriptor.of("auth.userdel", "Authentication", "Delete User")
                        .withArguments(new CommandDescriptor.Argument("username", "string", "username"))
                        .describedAs("Permanently removes a user account"),
                ctx -> {
                    String username = ctx.args().requiredString("username");
                    if (!users.exists(username)) {
                        throw ForgeException.notFound("User not found: " + username);
                    }
                    users.delete(username);
                    audit.record(AuditEntry.ofSuccess(
                            ctx.request().userId(), ctx.sessionId(), "auth.userdel", username, ""));
                    return null;
                });

        // ---- usermod (command) -------------------------------------------------------
        commands.register(
                CommandDescriptor.of("auth.usermod", "Authentication", "Modify User")
                        .withArguments(
                                new CommandDescriptor.Argument("username", "string", "username"),
                                new CommandDescriptor.Argument("role", "string", "new role (optional)"),
                                new CommandDescriptor.Argument("enabled", "boolean", "enable account (optional)"))
                        .describedAs("Changes a user's role or account status"),
                ctx -> {
                    String username = ctx.args().requiredString("username");
                    Optional<String> newRole = ctx.args().string("role");
                    boolean enabled = ctx.args().bool("enabled", true);

                    StoredUser existing = users.find(username)
                            .orElseThrow(() -> ForgeException.notFound("User not found: " + username));

                    StoredUser updated = existing;
                    if (newRole.isPresent()) {
                        String roleName = newRole.get();
                        try {
                            roles.role(roleName);
                        } catch (ForgeException e) {
                            throw ForgeException.invalidArgument("Unknown role: " + roleName);
                        }
                        updated = updated.withRole(roleName);
                    }
                    updated = updated.withEnabled(enabled);
                    users.update(updated);

                    audit.record(AuditEntry.ofSuccess(
                            ctx.request().userId(), ctx.sessionId(), "auth.usermod", username, ""));
                    return null;
                });

        // ---- chmod (command) ---------------------------------------------------------
        commands.register(
                CommandDescriptor.of("auth.chmod", "Authentication", "Change Role Permissions")
                        .withArguments(
                                new CommandDescriptor.Argument("role", "string", "role name"),
                                new CommandDescriptor.Argument("permissions", "array", "list of permissions"))
                        .describedAs("Updates the permission set of an existing role"),
                ctx -> {
                    String roleName = ctx.args().requiredString("role");
                    List<String> permissions = ctx.args().strings("permissions");

                    roles.updatePermissions(roleName, Set.copyOf(permissions));

                    audit.record(AuditEntry.ofSuccess(
                            ctx.request().userId(), ctx.sessionId(), "auth.chmod", roleName, ""));
                    return null;
                });

        // ---- su (command) ------------------------------------------------------------
        commands.register(
                CommandDescriptor.of("auth.su", "Authentication", "Impersonate User")
                        .withArguments(new CommandDescriptor.Argument("username", "string", "username to impersonate"))
                        .describedAs("Creates a session as another user (superadmin only)").asSensitive(),
                ctx -> {
                    String targetUsername = ctx.args().requiredString("username");
                    StoredUser target = users.find(targetUsername)
                            .orElseThrow(() -> ForgeException.notFound("User not found: " + targetUsername));

                    User impersonated = new User(target.id(), target.username(), provider.id(),
                            Map.of("role", target.role(), "provider", provider.id()));
                    SessionService.Issued issued = sessions.issue(impersonated, "su from " + ctx.sessionId());

                    audit.record(AuditEntry.ofSuccess(
                            ctx.request().userId(), ctx.sessionId(), "auth.su", targetUsername, ""));

                    return new LoginResult(issued.token(), issued.session().id(), impersonated, issued.expiresAt());
                });
    }
}