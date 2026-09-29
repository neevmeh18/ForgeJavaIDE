package dev.forge.core;

public record UserId(String value) implements Id {
    public UserId {
        value = IdValidator.check(value, "userId");
    }

    public static UserId of(String value) {
        return new UserId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
