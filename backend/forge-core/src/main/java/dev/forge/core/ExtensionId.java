package dev.forge.core;

public record ExtensionId(String value) implements Id {
    public ExtensionId {
        value = IdValidator.check(value, "extensionId");
    }

    public static ExtensionId of(String value) {
        return new ExtensionId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
