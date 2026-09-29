package dev.forge.app;

import dev.forge.core.command.CommandDescriptor;
import dev.forge.core.command.CommandExecutor;
import dev.forge.core.command.CommandRegistry;
import dev.forge.core.contrib.ContributionRegistry;
import dev.forge.core.extension.ExtensionRegistry;
import dev.forge.core.query.QueryRegistry;
import dev.forge.core.query.QueryDescriptor;
import java.util.List;

record CommandView(String id, String title, String category, String description,
                   boolean requiresWorkspace, boolean undoable, boolean sensitive, String source,
                   boolean paletteVisible, List<dev.forge.core.command.Argument> arguments) {
    static CommandView of(CommandDescriptor descriptor) {
        return new CommandView(descriptor.id().value(), descriptor.title(), descriptor.category(),
                descriptor.description(), descriptor.requiresWorkspace(), descriptor.undoable(),
                descriptor.sensitive(), descriptor.source(), true, descriptor.arguments());
    }
}
