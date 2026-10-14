package dev.forge.workspace;

import dev.forge.auth.SessionService;
import dev.forge.auth.User;
import dev.forge.core.ForgeException;
import dev.forge.core.Ids;
import dev.forge.core.Ids.SessionId;
import dev.forge.core.Ids.UserId;
import dev.forge.core.Ids.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Invitations and the role each session holds in a workspace.
 *
 * <p>An invitation is a one-time capability: the token is returned to the owner exactly once,
 * and only its hash is kept. Accepting one opens a guest session on that workspace. A person
 * who opens a workspace with their own account becomes its owner; a guest keeps the role the
 * invitation granted them.
 */
public final class WorkspaceAccessService {

    public enum Role {
        VIEWER,
        OWNER
    }

    /** An invitation as callers may see it. The token itself is never part of this value. */
    public record Invitation(
            String id,
            WorkspaceId workspaceId,
            Role role,
            Instant createdAt,
            Instant expiresAt) {
    }

    /** Returned once, from {@link #invite}. The raw token is not stored. */
    public record IssuedInvitation(Invitation invitation, String token) {
    }

    /** A guest session created by accepting an invitation. */
    public record AcceptedInvitation(
            String token,
            SessionId sessionId,
            User user,
            Instant expiresAt,
            WorkspaceId workspaceId,
            Role role) {
    }

    private record Stored(Invitation invitation, String tokenHash, UserId createdBy) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MIN_DAYS = 1;
    private static final int MAX_DAYS = 14;

    private final WorkspaceService workspaces;
    private final SessionService sessions;
    private final Map<String, Stored> byHash = new ConcurrentHashMap<>();
    private final Map<String, Stored> byId = new ConcurrentHashMap<>();
    private final Map<SessionId, Map<WorkspaceId, Role>> grants = new ConcurrentHashMap<>();
    private final Set<UserId> guests = ConcurrentHashMap.newKeySet();

    public WorkspaceAccessService(WorkspaceService workspaces, SessionService sessions) {
        this.workspaces = workspaces;
        this.sessions = sessions;
    }

    public IssuedInvitation invite(UserId owner, WorkspaceId workspace, Role role, int validDays) {
        if (validDays < MIN_DAYS || validDays > MAX_DAYS) {
            throw ForgeException.invalidArgument("Invitation lifetime must be between 1 and 14 days");
        }
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Arrays.fill(raw, (byte) 0);

        Instant now = Instant.now();
        Invitation invitation = new Invitation(
                Ids.random("invite"), workspace, role, now, now.plus(Duration.ofDays(validDays)));
        Stored stored = new Stored(invitation, hash(token), owner);
        byHash.put(stored.tokenHash(), stored);
        byId.put(invitation.id(), stored);
        return new IssuedInvitation(invitation, token);
    }

    public List<Invitation> list(WorkspaceId workspace) {
        Instant now = Instant.now();
        return byId.values().stream()
                .map(Stored::invitation)
                .filter(invitation -> invitation.workspaceId().equals(workspace))
                .filter(invitation -> invitation.expiresAt().isAfter(now))
                .sorted(Comparator.comparing(Invitation::createdAt).reversed())
                .toList();
    }

    public Invitation preview(String token) {
        return requireActive(token).invitation();
    }

    public AcceptedInvitation accept(String token, Role requestedRole, String clientInfo) {
        Stored stored = requireActive(token);
        Role granted = requestedRole;
        User guest = User.of(Ids.random("guest"), "Guest", "invitation");
        guests.add(guest.id());
        SessionService.Issued issued = sessions.issue(guest, clientInfo == null ? "" : clientInfo);
        WorkspaceId workspaceId = stored.invitation().workspaceId();
        workspaces.open(workspaceId, issued.session().id());
        grants.computeIfAbsent(issued.session().id(), key -> new ConcurrentHashMap<>())
                .put(workspaceId, granted);
        return new AcceptedInvitation(
                issued.token(), issued.session().id(), guest, issued.expiresAt(), workspaceId, granted);
    }

    public void revoke(WorkspaceId workspace, String invitationId) {
        Stored stored = byId.get(invitationId);
        if (stored == null || !stored.invitation().workspaceId().equals(workspace)) {
            throw ForgeException.notFound("Invitation not found");
        }
        byId.remove(invitationId, stored);
        byHash.remove(stored.tokenHash(), stored);
    }

    /**
     * Records a direct open.
     *
     * <p>A session that already holds a role keeps it. A new open by the account that signed in
     * to the IDE becomes owner. A guest cannot attach to a workspace they were not invited to.
     */
    public void noteOpened(SessionId session, WorkspaceId workspace, UserId user) {
        Map<WorkspaceId, Role> roles = grants.computeIfAbsent(session, key -> new ConcurrentHashMap<>());
        if (roles.containsKey(workspace)) {
            return;
        }
        if (guests.contains(user)) {
            throw ForgeException.forbidden("This session is not invited to that workspace");
        }
        roles.put(workspace, Role.OWNER);
    }

    public boolean isOwner(SessionId session, WorkspaceId workspace) {
        return roleOf(session, workspace) == Role.OWNER;
    }

    public boolean isGuest(UserId user) {
        return user != null && guests.contains(user);
    }

    public void forgetSession(SessionId session) {
        grants.remove(session);
    }

    private Role roleOf(SessionId session, WorkspaceId workspace) {
        Map<WorkspaceId, Role> roles = grants.get(session);
        return roles == null ? null : roles.get(workspace);
    }

    private Stored requireActive(String token) {
        if (token == null || token.isBlank()) {
            throw ForgeException.notFound("Invitation not found");
        }
        Stored stored = byHash.get(hash(token));
        if (stored == null || !stored.invitation().expiresAt().isAfter(Instant.now())) {
            if (stored != null) {
                byId.remove(stored.invitation().id(), stored);
                byHash.remove(stored.tokenHash(), stored);
            }
            throw ForgeException.notFound("Invitation not found");
        }
        return stored;
    }

    private static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Base64.getEncoder().encodeToString(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw ForgeException.internal("SHA-256 unavailable", e);
        }
    }
}
