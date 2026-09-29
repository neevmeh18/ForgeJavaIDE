package dev.forge.auth;

import dev.forge.core.SessionId;
import dev.forge.core.UserId;
import dev.forge.core.event.Event;

public record SessionStarted(SessionId sessionId, UserId userId) implements Event {
    @Override
    public String type() {
        return "auth.sessionStarted";
    }
}
