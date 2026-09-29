package dev.forge.core;

public record SessionId(String value) implements Id {
    public SessionId {
        value = IdValidator.check(value, "sessionId");
    }

    public static SessionId of(String value) {
        return new SessionId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
