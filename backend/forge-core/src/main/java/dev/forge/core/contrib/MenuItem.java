package dev.forge.core.contrib;

import dev.forge.core.ExtensionId;
import dev.forge.core.Disposable;
import dev.forge.core.command.CommandId;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public record MenuItem(String menu, CommandId command, String title, String group, int order, String source) {
    public static MenuItem of(String menu, String command, String title, String group, int order) {
        return new MenuItem(menu, CommandId.of(command), title, group, order, "builtin");
    }
}
