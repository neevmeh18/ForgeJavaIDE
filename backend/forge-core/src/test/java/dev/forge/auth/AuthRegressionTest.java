package dev.forge.auth;

import dev.forge.core.*;
import dev.forge.core.Ids.*;
import dev.forge.core.command.CommandContext;
import dev.forge.core.command.CommandDescriptor;
import dev.forge.core.command.CommandId;
import dev.forge.core.event.EventBus;
import dev.forge.filesystem.FileSystem;
import dev.forge.workspace.Workspace;
import dev.forge.workspace.WorkspaceProvider;
import dev.forge.workspace.WorkspaceService;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Regression tests for the multiuser authentication subsystem.
 *
 * <p>Uses in-memory stubs for {@link UserStore} and {@link RoleService} so these tests
 * depend only on forge-core classes — no Jackson serialization or file I/O.
 */
public class AuthRegressionTest {

    private static final String USERNAME = "regtest";

    // ---- PasswordHasher ----------------------------------------------------------------

    @Test public void passwordHasherHashesAndVerifies() {
        byte[] salt = PasswordHasher.generateSalt();
        byte[] hash = PasswordHasher.hash("correct-password".toCharArray(), salt);
        assertNotNull(hash);
        assertTrue(PasswordHasher.verify("correct-password".toCharArray(), salt, hash));
    }

    @Test public void passwordHasherRejectsWrongPassword() {
        byte[] salt = PasswordHasher.generateSalt();
        byte[] hash = PasswordHasher.hash("real-password".toCharArray(), salt);
        assertFalse(PasswordHasher.verify("wrong-password".toCharArray(), salt, hash));
    }

    @Test public void passwordHasherRejectsEmptyPassword() {
        byte[] salt = PasswordHasher.generateSalt();
        byte[] hash = PasswordHasher.hash("".toCharArray(), salt);
        assertFalse(PasswordHasher.verify("anything".toCharArray(), salt, hash));
    }

    // ---- Authorizer + RBAC ------------------------------------------------------------

    @Test public void authorizerGrantsCorrectPermissions() throws Exception {
        var sessions = new SessionService(new EventBus(), Duration.ofMinutes(5), Duration.ofHours(1), 10);
        var roleService = new StubRoleService();
        var workspaces = new WorkspaceService(new EventBus(), List.of(new StubWorkspaceProvider()), 10);
        var authorizer = new Authorizer(workspaces, sessions, roleService);
        var user = new User(UserId.of("auth-test"), "Auth Test", "stub", Map.of());
        var issued = sessions.issue(user, "browser");
        var sid = issued.session().id();

        // file.read requires FILE_SEE — developer role grants it -> authorized
        authorizer.authorize(
                CommandDescriptor.of("file.read", "Test", "test"),
                new CommandContext(CommandId.of("file.read"), Args.EMPTY,
                        new RequestContext(user.id(), sid, null, RequestContext.Origin.UI, Cancellation.none()),
                        "exec1"));

        // file.delete requires FILE_DELETE — developer role does NOT grant it -> FORBIDDEN
        assertThrows(ForgeException.class, () -> authorizer.authorize(
                CommandDescriptor.of("file.delete", "Test", "test"),
                new CommandContext(CommandId.of("file.delete"), Args.EMPTY,
                        new RequestContext(user.id(), sid, null, RequestContext.Origin.UI, Cancellation.none()),
                        "exec2")));
        sessions.dispose();
    }

    @Test public void authorizerDeniesWithoutSession() {
        var sessions = new SessionService(new EventBus(), Duration.ofMinutes(5), Duration.ofHours(1), 10);
        var roleService = new StubRoleService();
        var workspaces = new WorkspaceService(new EventBus(), List.of(new StubWorkspaceProvider()), 10);
        var authorizer = new Authorizer(workspaces, sessions, roleService);

        assertThrows(ForgeException.class, () -> authorizer.authorize(
                CommandDescriptor.of("file.read", "Test", "test"),
                new CommandContext(CommandId.of("file.read"), Args.EMPTY,
                        new RequestContext(UserId.of("anon"), SessionId.of("no-such-session"),
                                null, RequestContext.Origin.UI, Cancellation.none()),
                        "exec3")));
        sessions.dispose();
    }

    @Test public void authorizerGrantsSuperadmin() throws Exception {
        var sessions = new SessionService(new EventBus(), Duration.ofMinutes(5), Duration.ofHours(1), 10);
        var roleService = new StubRoleService();
        var workspaces = new WorkspaceService(new EventBus(), List.of(new StubWorkspaceProvider()), 10);
        var authorizer = new Authorizer(workspaces, sessions, roleService);
        var user = new User(UserId.of("su-test"), "SU Test", "stub",
                Map.of("role", "superadmin"));
        var issued = sessions.issue(user, "browser");
        var sid = issued.session().id();

        // superadmin grants everything
        authorizer.authorize(
                CommandDescriptor.of("file.delete", "Test", "delete"),
                new CommandContext(CommandId.of("file.delete"), Args.EMPTY,
                        new RequestContext(user.id(), sid, null, RequestContext.Origin.UI, Cancellation.none()),
                        "exec4"));
        authorizer.authorize(
                CommandDescriptor.of("auth.su", "Test", "su"),
                new CommandContext(CommandId.of("auth.su"), Args.EMPTY,
                        new RequestContext(user.id(), sid, null, RequestContext.Origin.UI, Cancellation.none()),
                        "exec5"));
        sessions.dispose();
    }

    // ---- MultiuserAuthenticationProvider ----------------------------------------------

    @Test public void multiuserAuthProviderAcceptsValidCredentials() {
        var userStore = new StubUserStore();
        var provider = new MultiuserAuthenticationProvider(userStore);
        var credentials = new AuthenticationProvider.Credentials(
                USERNAME, "admin-password".toCharArray(), Map.of());
        try {
            User result = provider.authenticate(credentials)
                    .orElseThrow(() -> new AssertionError("Expected successful authentication"));
            assertEquals(USERNAME, result.id().value());
            assertEquals("admin", result.role());
        } finally {
            credentials.wipe();
        }
    }

    @Test public void multiuserAuthProviderRejectsBadCredentials() {
        var userStore = new StubUserStore();
        var provider = new MultiuserAuthenticationProvider(userStore);
        var credentials = new AuthenticationProvider.Credentials(
                USERNAME, "wrong-password".toCharArray(), Map.of());
        try {
            assertTrue(provider.authenticate(credentials).isEmpty());
        } finally {
            credentials.wipe();
        }
    }

    @Test public void multiuserAuthProviderRejectsDisabledUser() {
        var userStore = new StubUserStore();
        var provider = new MultiuserAuthenticationProvider(userStore);
        var credentials = new AuthenticationProvider.Credentials(
                "disabled-user", "password".toCharArray(), Map.of());
        try {
            assertTrue(provider.authenticate(credentials).isEmpty());
        } finally {
            credentials.wipe();
        }
    }

    @Test public void multiuserAuthProviderRejectsUnknownUsername() {
        var userStore = new StubUserStore();
        var provider = new MultiuserAuthenticationProvider(userStore);
        var credentials = new AuthenticationProvider.Credentials(
                "no-such-user", "anything".toCharArray(), Map.of());
        try {
            assertTrue(provider.authenticate(credentials).isEmpty());
        } finally {
            credentials.wipe();
        }
    }

    @Test public void rateLimitBlocksAfterExcessAttempts() {
        var userStore = new StubUserStore();
        var provider = new MultiuserAuthenticationProvider(userStore);
        for (int i = 0; i < 10; i++) {
            var credentials = new AuthenticationProvider.Credentials(
                    USERNAME, "wrong-password".toCharArray(), Map.of());
            try {
                provider.authenticate(credentials);
            } catch (ForgeException e) {
                // Expected for each failed attempt
            } finally {
                credentials.wipe();
            }
        }
        // 11th attempt should be rate limited
        var finalCredentials = new AuthenticationProvider.Credentials(
                USERNAME, "wrong-password".toCharArray(), Map.of());
        try {
            var ex = assertThrows(ForgeException.class,
                    () -> provider.authenticate(finalCredentials));
            assertEquals(ForgeException.Code.UNAUTHORIZED, ex.code());
        } finally {
            finalCredentials.wipe();
        }
    }

    // ---- SessionService role tracking --------------------------------------------------

    @Test public void sessionServiceTracksRole() throws Exception {
        var sessions = new SessionService(new EventBus(), Duration.ofMinutes(5), Duration.ofHours(1), 10);
        var user = new User(UserId.of("role-test"), "Role Test", "stub",
                Map.of("role", "superadmin"));
        var issued = sessions.issue(user, "browser");
        var sid = issued.session().id();
        var roleOpt = sessions.roleOf(sid);
        assertTrue(roleOpt.isPresent());
        assertEquals("superadmin", roleOpt.get());
        sessions.dispose();
    }

    @Test public void sessionServiceDefaultsToDeveloperRole() throws Exception {
        var sessions = new SessionService(new EventBus(), Duration.ofMinutes(5), Duration.ofHours(1), 10);
        var user = User.of("default-role", "No Role", "stub");
        var issued = sessions.issue(user, "browser");
        var sid = issued.session().id();
        var roleOpt = sessions.roleOf(sid);
        assertTrue(roleOpt.isPresent());
        assertEquals("developer", roleOpt.get());
        sessions.dispose();
    }

    @Test public void sessionServiceFindByUserReturnsSessions() throws Exception {
        var sessions = new SessionService(new EventBus(), Duration.ofMinutes(5), Duration.ofHours(1), 10);
        var uid = UserId.of("find-user");
        var user = User.of(uid.value(), "Find User", "stub");
        var s1 = sessions.issue(user, "browser");
        var s2 = sessions.issue(user, "api");
        var found = sessions.findByUser(uid);
        assertEquals(2, found.size());
        assertTrue(found.stream().anyMatch(s -> s.id().equals(s1.session().id())));
        assertTrue(found.stream().anyMatch(s -> s.id().equals(s2.session().id())));
        sessions.dispose();
    }

    @Test public void roleIsCleanedOnSessionRevoke() throws Exception {
        var sessions = new SessionService(new EventBus(), Duration.ofMinutes(5), Duration.ofHours(1), 10);
        var user = new User(UserId.of("cleanup-test"), "Cleanup", "stub",
                Map.of("role", "developer"));
        var issued = sessions.issue(user, "browser");
        var sid = issued.session().id();
        assertTrue(sessions.roleOf(sid).isPresent());
        sessions.revoke(sid, "logout");
        assertTrue(sessions.roleOf(sid).isEmpty());
        sessions.dispose();
    }

    // ---- Stub implementations ----------------------------------------------------------

    /** A WorkspaceProvider that discovers no workspaces. */
    static class StubWorkspaceProvider implements WorkspaceProvider {
        @Override public String scheme() { return "test"; }
        @Override public List<Workspace> discover() { return List.of(); }
        @Override public Optional<Workspace> find(WorkspaceId id) { return Optional.empty(); }
        @Override public Workspace create(String name, Map<String, String> options) {
            throw new UnsupportedOperationException();
        }
        @Override public Workspace open(WorkspaceId id) { throw new UnsupportedOperationException(); }
        @Override public void close(WorkspaceId id) { }
        @Override public FileSystem fileSystem(WorkspaceId id) {
            throw new UnsupportedOperationException();
        }
    }

    /** A UserStore that holds a few pre-configured accounts in memory. */
    static class StubUserStore implements UserStore {
        private final Map<String, StoredUser> users = new ConcurrentHashMap<>();

        StubUserStore() {
            addUser("regtest", "admin", "admin-password");
            addUser("disabled-user", "developer", "password");
            StoredUser disabled = users.get("disabled-user");
            users.put("disabled-user", disabled.withEnabled(false));
        }

        private void addUser(String username, String role, String password) {
            byte[] salt = PasswordHasher.generateSalt();
            byte[] hash = PasswordHasher.hash(password.toCharArray(), salt);
            users.put(username, StoredUser.of(
                    UserId.of(username), username,
                    Base64.getEncoder().encodeToString(hash),
                    Base64.getEncoder().encodeToString(salt),
                    role));
        }

        @Override public Optional<StoredUser> find(String username) {
            return Optional.ofNullable(users.get(username));
        }
        @Override public List<StoredUser> list() { return List.copyOf(users.values()); }
        @Override public void create(StoredUser user) {
            if (users.containsKey(user.username()))
                throw ForgeException.conflict("User already exists: " + user.username());
            users.put(user.username(), user);
        }
        @Override public void update(StoredUser user) {
            if (!users.containsKey(user.username()))
                throw ForgeException.notFound("User not found: " + user.username());
            users.put(user.username(), user);
        }
        @Override public void delete(String username) { users.remove(username); }
        @Override public boolean exists(String username) { return users.containsKey(username); }
        @Override public int count() { return users.size(); }
        @Override public void flush() { }
    }

    /** A RoleService with the four built-in roles. */
    static class StubRoleService implements RoleService {
        private static final Set<String> GUEST = Set.of(PermissionConstants.FILE_SEE);
        private static final Set<String> DEVELOPER = Set.of(
                PermissionConstants.FILE_SEE, PermissionConstants.FILE_EDIT, PermissionConstants.FILE_CREATE);
        private static final Set<String> ADMIN = Set.of(
                PermissionConstants.FILE_SEE, PermissionConstants.FILE_EDIT,
                PermissionConstants.FILE_CREATE, PermissionConstants.FILE_DELETE,
                PermissionConstants.USER_ADMIN);
        private static final Set<String> SUPERADMIN = Set.of(
                PermissionConstants.FILE_SEE, PermissionConstants.FILE_EDIT,
                PermissionConstants.FILE_CREATE, PermissionConstants.FILE_DELETE,
                PermissionConstants.USER_ADMIN, PermissionConstants.ROLE_ADMIN,
                PermissionConstants.AUDIT_VIEW, PermissionConstants.SU);

        StubRoleService() {
            var roles = Map.of(
                    "guest", new Role("guest", "Guest", GUEST),
                    "developer", new Role("developer", "Developer", DEVELOPER),
                    "admin", new Role("admin", "Admin", ADMIN),
                    "superadmin", new Role("superadmin", "Superadmin", SUPERADMIN)
            );
            for (var entry : roles.entrySet()) {
                createRole(entry.getValue());
            }
        }

        private final Map<String, Role> roles = new ConcurrentHashMap<>();

        @Override public Role role(String name) {
            return roles.getOrDefault(name, roles.get("guest"));
        }
        @Override public List<Role> listRoles() { return List.copyOf(roles.values()); }
        @Override public void createRole(Role role) { roles.put(role.name(), role); }
        @Override public Role updatePermissions(String name, Set<String> permissions) {
            Role existing = role(name);
            Role updated = existing.withPermissions(permissions);
            roles.put(name, updated);
            return updated;
        }
        @Override public void deleteRole(String name) { roles.remove(name); }
        @Override public Set<String> permissionsOfRole(String name) {
            return role(name).permissions();
        }
    }
}