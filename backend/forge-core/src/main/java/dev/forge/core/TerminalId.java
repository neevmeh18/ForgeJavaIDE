package dev.forge.core;

public record TerminalId(String value) implements Id {
    public TerminalId {
        value = IdValidator.check(value, "terminalId");
    }

    public static TerminalId of(String value) {
        return new TerminalId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
