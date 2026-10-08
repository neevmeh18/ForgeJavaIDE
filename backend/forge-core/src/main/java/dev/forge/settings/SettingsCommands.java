package dev.forge.settings;

import dev.forge.core.ForgeException;
import dev.forge.core.command.CommandDescriptor;
import dev.forge.core.command.CommandRegistry;
import dev.forge.core.contrib.ContributionRegistry;
import dev.forge.core.query.QueryRegistry;
import dev.forge.core.query.QueryRegistry.QueryDescriptor;
import java.util.Locale;

/** {@code settings.*} commands and queries. */
public final class SettingsCommands {

    private final SettingsService settings;

    public SettingsCommands(SettingsService settings) {
        this.settings = settings;
    }

    public void register(CommandRegistry commands, QueryRegistry queries, ContributionRegistry contributions) {
        commands.register(
                CommandDescriptor.of("settings.set", "Settings", "Change Setting") .withArguments(new CommandDescriptor.Argument("key", "string", "key"), new CommandDescriptor.Argument("value", "json", "value"))
                        .describedAs("Writes a setting into the user or workspace layer"),
                ctx -> settings.set(
                        ctx.args().requiredString("key"),
                        ctx.args().raw("value"),
                        layer(ctx.args().string("layer").orElse("user")),
                        ctx.userId(),
                        ctx.workspaceId()));

        queries.register(
                QueryDescriptor.of("settings.resolved", "Effective settings for this user and workspace"),
                (ctx, args) -> settings.resolveAll(ctx.userId(), ctx.workspaceId()));

        queries.register(
                QueryDescriptor.of("settings.definitions", "Declared settings and their types"),
                (ctx, args) -> settings.definitions());

        contributions.addMenuItem(ContributionRegistry.MenuItem.of(
                ContributionRegistry.MENU_VIEW, "workbench.view.settings", "Settings", "view", 90));
    }

    private static Settings.Layer layer(String raw) {
        try {
            return Settings.Layer.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ForgeException.invalidArgument("Unknown settings layer: " + raw);
        }
    }
}
