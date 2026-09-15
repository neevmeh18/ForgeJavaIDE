package dev.forge.core.contrib;

import dev.forge.core.Ids.ExtensionId;
import dev.forge.core.Lifecycle.Disposable;
import dev.forge.core.command.CommandId;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Declarative contribution points: menus, keybindings and views.
 *
 * <p>Each one is just a binding from a place in the UI to a {@link CommandId}. That indirection
 * is the point — {@code Ctrl+S}, the File menu, a toolbar button and the command palette all
 * resolve to {@code file.save}, so the action exists once and every route reaches the same
 * handler with the same checks.
 *
 * <p>The frontend reads this registry through the {@code workbench.contributions} query and
 * renders whatever it finds, which is how an extension gets menu items and shortcuts without
 * the frontend shipping any knowledge of it.
 */
public final class ContributionRegistry {

    /** Where a menu item appears. Products may define their own additional locations. */
    public static final String MENU_FILE = "menu.file";
    public static final String MENU_EDIT = "menu.edit";
    public static final String MENU_VIEW = "menu.view";
    public static final String MENU_EXPLORER_CONTEXT = "menu.explorer.context";
    public static final String MENU_EDITOR_TITLE = "menu.editor.title";

    public record MenuItem(String menu, CommandId command, String title, String group, int order, String source) {
        public static MenuItem of(String menu, String command, String title, String group, int order) {
            return new MenuItem(menu, CommandId.of(command), title, group, order, "builtin");
        }
    }

    /**
     * A keystroke bound to a command. {@code key} uses a portable spelling such as
     * {@code ctrl+s} or {@code ctrl+shift+p}; the frontend maps {@code ctrl} to the platform
     * modifier. {@code when} is an optional UI context hint like {@code editorFocus}.
     */
    public record Keybinding(String key, CommandId command, String when, String source) {
        public static Keybinding of(String key, String command, String when) {
            return new Keybinding(key, CommandId.of(command), when, "builtin");
        }
    }

    /** A panel or sidebar view. {@code container} is e.g. {@code sidebar} or {@code panel}. */
    public record View(String id, String title, String container, String icon, int order, String source) {
        public static View of(String id, String title, String container, String icon, int order) {
            return new View(id, title, container, icon, order, "builtin");
        }
    }

    private final List<MenuItem> menuItems = new CopyOnWriteArrayList<>();
    private final List<Keybinding> keybindings = new CopyOnWriteArrayList<>();
    private final List<View> views = new CopyOnWriteArrayList<>();

    public Disposable addMenuItem(MenuItem item) {
        menuItems.add(item);
        return () -> menuItems.remove(item);
    }

    public Disposable addKeybinding(Keybinding binding) {
        keybindings.add(binding);
        return () -> keybindings.remove(binding);
    }

    public Disposable addView(View view) {
        views.add(view);
        return () -> views.remove(view);
    }

    public List<MenuItem> menuItems() {
        return menuItems.stream()
                .sorted(Comparator.comparing(MenuItem::menu).thenComparingInt(MenuItem::order))
                .toList();
    }

    public List<Keybinding> keybindings() {
        return List.copyOf(keybindings);
    }

    public List<View> views() {
        return views.stream().sorted(Comparator.comparingInt(View::order)).toList();
    }

    public void removeAllFrom(ExtensionId extension) {
        String source = extension.value();
        menuItems.removeIf(item -> source.equals(item.source()));
        keybindings.removeIf(binding -> source.equals(binding.source()));
        views.removeIf(view -> source.equals(view.source()));
    }
}
