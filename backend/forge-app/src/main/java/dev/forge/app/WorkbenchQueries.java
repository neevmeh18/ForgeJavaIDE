package dev.forge.app;

import dev.forge.core.command.CommandDescriptor;
import dev.forge.core.command.CommandExecutor;
import dev.forge.core.command.CommandRegistry;
import dev.forge.core.contrib.ContributionRegistry;
import dev.forge.core.extension.ExtensionRegistry;
import dev.forge.core.query.QueryRegistry;
import dev.forge.core.query.QueryDescriptor;
import java.util.List;













final class WorkbenchQueries {








    private final CommandRegistry commands;
    private final CommandExecutor executor;
    private final ContributionRegistry contributions;
    private final ExtensionRegistry extensions;
    private final dev.forge.language.LanguageService languages;
    private final boolean terminalsAvailable;

    WorkbenchQueries(CommandRegistry commands, CommandExecutor executor, ContributionRegistry contributions,
                     ExtensionRegistry extensions, dev.forge.language.LanguageService languages,
                     boolean terminalsAvailable) {
        this.commands = commands;
        this.executor = executor;
        this.contributions = contributions;
        this.extensions = extensions;
        this.languages = languages;
        this.terminalsAvailable = terminalsAvailable;
    }

    void register(QueryRegistry queries, CommandRegistry registry) {
        queries.register(
                QueryDescriptor.of("workbench.commands", "Every command the palette may offer"),
                (ctx, args) -> commands.list().stream().map(CommandView::of).toList());

        queries.register(
                QueryDescriptor.of("workbench.contributions", "Menus, keybindings and views"),
                (ctx, args) -> new Contributions(contributions.menuItems(), contributions.keybindings(),
                        contributions.views()));

        queries.register(
                QueryDescriptor.of("workbench.extensions", "Installed extensions and their state"),
                (ctx, args) -> extensions.list());

        queries.register(
                QueryDescriptor.of("workbench.status", "What this deployment offers").anonymous(),
                (ctx, args) -> new Status(Forge.PRODUCT, Forge.VERSION, commands.list().size(),
                        extensions.list().size(), languages.supportedLanguages(), terminalsAvailable));



        registry.register(
                CommandDescriptor.of("command.cancel", "Workbench", "Cancel Running Command") .withArguments(new dev.forge.core.command.Argument("executionId", "string", "executionId"))
                        .describedAs("Requests cancellation of an in-flight command execution"),
                ctx -> executor.cancel(ctx.args().requiredString("executionId"), ctx.sessionId()));

        registry.register(CommandDescriptor.of("extension.deactivate", "Extensions", "Deactivate Extension") .withArguments(new dev.forge.core.command.Argument("extensionId", "string", "extensionId")).asSensitive(),
                ctx -> { extensions.deactivate(dev.forge.core.ExtensionId.of(ctx.args().requiredString("extensionId"))); return null; });

        registry.register(
                CommandDescriptor.of("extension.activate", "Extensions", "Activate Extension") .withArguments(new dev.forge.core.command.Argument("extensionId", "string", "extensionId")).asSensitive(),
                ctx -> {
                    extensions.activate(dev.forge.core.ExtensionId.of(
                            ctx.args().requiredString("extensionId")));
                    return extensions.status(dev.forge.core.ExtensionId.of(
                            ctx.args().requiredString("extensionId"))).orElse(null);
                });
    }
}
