package dev.forge.app;

import dev.forge.core.command.CommandDescriptor;
import dev.forge.core.command.CommandExecutor;
import dev.forge.core.command.CommandRegistry;
import dev.forge.core.contrib.ContributionRegistry;
import dev.forge.core.extension.ExtensionRegistry;
import dev.forge.core.query.QueryRegistry;
import dev.forge.core.query.QueryDescriptor;
import java.util.List;

record Contributions(List<dev.forge.core.contrib.MenuItem> menus,
                     List<dev.forge.core.contrib.Keybinding> keybindings,
                     List<dev.forge.core.contrib.View> views) {
}
