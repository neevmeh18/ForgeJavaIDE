package dev.forge.core;

public record TaskExecutionId(String value) implements Id {
    public TaskExecutionId {
        value = IdValidator.check(value, "taskExecutionId");
    }

    public static TaskExecutionId of(String value) {
        return new TaskExecutionId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
