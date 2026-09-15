package dev.forge.auth;

import dev.forge.core.Ids.SessionId;
import dev.forge.core.Ids.UserId;
import dev.forge.core.event.Event;

/** Session lifecycle events. Never carry tokens. */
public final class AuthEvents {

    private AuthEvents() {
    }

    public record SessionStarted(SessionId sessionId, UserId userId) implements Event {
        @Override
        public String type() {
            return "auth.sessionStarted";
        }
    }

    public record SessionEnded(SessionId sessionId, UserId userId, String reason) implements Event {
        @Override
        public String type() {
            return "auth.sessionEnded";
        }
    }
}
