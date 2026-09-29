package dev.forge.app;

import dev.forge.core.command.CommandDescriptor;
import dev.forge.core.command.CommandExecutor;
import dev.forge.core.command.CommandRegistry;
import dev.forge.core.contrib.ContributionRegistry;
import dev.forge.core.extension.ExtensionRegistry;
import dev.forge.core.query.QueryRegistry;
import dev.forge.core.query.QueryDescriptor;
import java.util.List;

record Status(String product, String version, int commandCount, int extensionCount,
              List<String> languages, boolean terminalsAvailable) {
}
