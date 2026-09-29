package dev.forge.core;

public record DebugSessionId(String value) implements Id {
    public DebugSessionId {
        value = IdValidator.check(value, "debugSessionId");
    }

    public static DebugSessionId of(String value) {
        return new DebugSessionId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
