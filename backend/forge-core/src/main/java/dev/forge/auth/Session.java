package dev.forge.auth;

import dev.forge.core.SessionId;
import dev.forge.core.UserId;
import java.time.Instant;












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
