package dev.forge.auth;

import dev.forge.core.Ids.SessionId;
import dev.forge.core.Ids.UserId;
import java.time.Instant;

/**
 * One user's active interaction with the IDE.
 *
 * <p>Kept strictly apart from {@code Workspace}: a workspace is a persistent environment, a
 * session is transient and belongs to one client. Several sessions can attach to the same
 * workspace, and one user can hold several sessions (two browser tabs, a CLI). Everything that
 * later becomes presence, remote cursors or follow mode hangs off this distinction.
 *
 * <p>The bearer token is not part of this record — only its hash lives in {@link SessionService},
 * so a session object can be logged or serialised without leaking credentials.
 */
public record Session(
        SessionId id,
        UserId userId,
        Instant createdAt,
        Instant lastSeenAt,
        Instant expiresAt,
        String clientInfo) {

    public boolean isExpired(Instant now) {
        return now.isAfter(expiresAt);
    }

    public Session seen(Instant now, Instant newExpiry) {
        return new Session(id, userId, createdAt, now, newExpiry, clientInfo);
    }
}
