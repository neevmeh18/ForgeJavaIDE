package dev.forge.app;

import dev.forge.core.command.CommandDescriptor;
import dev.forge.core.command.CommandExecutor;
import dev.forge.core.command.CommandRegistry;
import dev.forge.core.contrib.ContributionRegistry;
import dev.forge.core.extension.ExtensionRegistry;
import dev.forge.core.query.QueryRegistry;
import dev.forge.core.query.QueryRegistry.QueryDescriptor;
import java.util.List;

/**
 * What the workbench needs in order to render itself.
 *
 * <p>The frontend ships no hard-coded list of commands, menus, shortcuts or views: it asks for
 * them. That is what makes an extension's contributions appear in the palette and the menu bar
 * without the UI knowing the extension exists — and what keeps a product able to change its
 * command set without rebuilding the frontend.
 *
 * <p>Descriptors are projected into explicit view records rather than serialised directly: a
 * {@link CommandDescriptor} carries an availability function, which is behaviour and has no
 * business on the wire.
 */
final class WorkbenchQueries {

    /** A command as the palette sees it. */
    record CommandView(String id, String title, String category, String description,
                       boolean requiresWorkspace, boolean undoable, boolean sensitive, String source,
                       boolean paletteVisible, List<CommandDescriptor.Argument> arguments) {
        static CommandView of(CommandDescriptor descriptor) {
            return new CommandView(descriptor.id().value(), descriptor.title(), descriptor.category(),
                    descriptor.description(), descriptor.requiresWorkspace(), descriptor.undoable(),
                    descriptor.sensitive(), descriptor.source(), true, descriptor.arguments());
        }
    }

    record Contributions(List<ContributionRegistry.MenuItem> menus,
                         List<ContributionRegistry.Keybinding> keybindings,
                         List<ContributionRegistry.View> views) {
    }

    record Status(String product, String version, int commandCount, int extensionCount,
                  List<String> languages, boolean terminalsAvailable) {
    }

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

        // Cancellation is itself a command, so cancelling works identically from the palette, a
        // keybinding, the CLI or an automation client.
        registry.register(
                CommandDescriptor.of("command.cancel", "Workbench", "Cancel Running Command") .withArguments(new CommandDescriptor.Argument("executionId", "string", "executionId"))
                        .describedAs("Requests cancellation of an in-flight command execution"),
                ctx -> executor.cancel(ctx.args().requiredString("executionId"), ctx.sessionId()));

        registry.register(CommandDescriptor.of("extension.deactivate", "Extensions", "Deactivate Extension") .withArguments(new CommandDescriptor.Argument("extensionId", "string", "extensionId")).asSensitive(),
                ctx -> { extensions.deactivate(dev.forge.core.Ids.ExtensionId.of(ctx.args().requiredString("extensionId"))); return null; });

        registry.register(
                CommandDescriptor.of("extension.activate", "Extensions", "Activate Extension") .withArguments(new CommandDescriptor.Argument("extensionId", "string", "extensionId")).asSensitive(),
                ctx -> {
                    extensions.activate(dev.forge.core.Ids.ExtensionId.of(
                            ctx.args().requiredString("extensionId")));
                    return extensions.status(dev.forge.core.Ids.ExtensionId.of(
                            ctx.args().requiredString("extensionId"))).orElse(null);
                });
    }
}
