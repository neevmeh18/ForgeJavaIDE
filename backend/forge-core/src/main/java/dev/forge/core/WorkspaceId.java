package dev.forge.core;

public record WorkspaceId(String value) implements Id {
    public WorkspaceId {
        value = IdValidator.check(value, "workspaceId");
    }

    public static WorkspaceId of(String value) {
        return new WorkspaceId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
