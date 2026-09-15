package dev.forge.core.command;

import dev.forge.core.ForgeException;
import java.util.regex.Pattern;

/**
 * A stable, namespaced command identifier such as {@code file.save} or {@code robotics.deploy}.
 *
 * <p>The namespace is mandatory. A bare {@code run}, {@code open} or {@code deploy} is rejected
 * at construction: once extensions can contribute commands, unqualified verbs collide and there
 * is no way to tell whose {@code deploy} a keybinding meant.
 */
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

    /** The part before the first dot: {@code file} for {@code file.save}. */
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
