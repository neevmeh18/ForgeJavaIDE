package dev.forge.core.contrib;

import dev.forge.core.ExtensionId;
import dev.forge.core.Disposable;
import dev.forge.core.command.CommandId;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public record View(String id, String title, String container, String icon, int order, String source) {
    public static View of(String id, String title, String container, String icon, int order) {
        return new View(id, title, container, icon, order, "builtin");
    }
}
