package dev.forge.core;

public record DocumentId(String value) implements Id {
    public DocumentId {
        value = IdValidator.check(value, "documentId");
    }

    public static DocumentId of(String value) {
        return new DocumentId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
