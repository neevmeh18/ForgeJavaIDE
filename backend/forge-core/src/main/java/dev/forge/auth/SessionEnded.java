package dev.forge.auth;

import dev.forge.core.SessionId;
import dev.forge.core.UserId;
import dev.forge.core.event.Event;

public record SessionEnded(SessionId sessionId, UserId userId, String reason) implements Event {
    @Override
    public String type() {
        return "auth.sessionEnded";
    }
}
