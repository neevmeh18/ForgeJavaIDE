package dev.forge.core.contrib;

import dev.forge.core.ExtensionId;
import dev.forge.core.Disposable;
import dev.forge.core.command.CommandId;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;













public final class ContributionRegistry {


    public static final String MENU_FILE = "menu.file";
    public static final String MENU_EDIT = "menu.edit";
    public static final String MENU_VIEW = "menu.view";
    public static final String MENU_EXPLORER_CONTEXT = "menu.explorer.context";
    public static final String MENU_EDITOR_TITLE = "menu.editor.title";













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
