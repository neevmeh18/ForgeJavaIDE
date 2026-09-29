package dev.forge.core.command;

import dev.forge.core.ForgeException;
import java.util.regex.Pattern;








public record CommandId(String value) implements Comparable<CommandId> {

    private static final Pattern VALID =
            Pattern.compile("[a-z][a-zA-Z0-9]*(\\.[a-zA-Z0-9][a-zA-Z0-9-]*)+");

    public CommandId {
        if (value == null || !VALID.matcher(value).matches()) {
            throw ForgeException.invalidArgument(
                    "Command id must be namespaced, e.g. 'file.save' (was: " + value + ")");
        }
    }

    public static CommandId of(String value) {
        return new CommandId(value);
    }


    public String namespace() {
        return value.substring(0, value.indexOf('.'));
    }

    @Override
    public int compareTo(CommandId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
