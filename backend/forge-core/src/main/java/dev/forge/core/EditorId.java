package dev.forge.core;

public record EditorId(String value) implements Id {
    public EditorId {
        value = IdValidator.check(value, "editorId");
    }

    public static EditorId of(String value) {
        return new EditorId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
