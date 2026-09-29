package dev.forge.core.contrib;

import dev.forge.core.ExtensionId;
import dev.forge.core.Disposable;
import dev.forge.core.command.CommandId;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public record Keybinding(String key, CommandId command, String when, String source) {
    public static Keybinding of(String key, String command, String when) {
        return new Keybinding(key, CommandId.of(command), when, "builtin");
    }
}
