package dev.forge.terminal;

import dev.forge.core.command.CommandDescriptor;
import dev.forge.core.command.CommandRegistry;
import dev.forge.core.contrib.ContributionRegistry;
import dev.forge.core.query.QueryRegistry;
import dev.forge.core.query.QueryDescriptor;








public final class TerminalCommands {

    private final TerminalService terminals;

    public TerminalCommands(TerminalService terminals) {
        this.terminals = terminals;
    }

    public void register(CommandRegistry commands, QueryRegistry queries, ContributionRegistry contributions) {
        commands.register(
                CommandDescriptor.of("terminal.create", "Terminal", "New Terminal")
                        .describedAs("Starts a shell in the workspace")
                        .workspaceScoped().asSensitive(),
                ctx -> terminals.create(ctx.requireWorkspace(),
                        ctx.args().string("cwd").orElse(""),
                        ctx.args().string("title").orElse(null),
                        ctx.args().nested("env").values(),
                        ctx.args().integer("columns", 80),
                        ctx.args().integer("rows", 24)));

        commands.register(
                CommandDescriptor.of("terminal.write", "Terminal", "Send Terminal Input") .withArguments(new dev.forge.core.command.Argument("terminalId", "string", "terminalId"), new dev.forge.core.command.Argument("data", "string", "data"))
                        .workspaceScoped().asSensitive(),
                ctx -> {
                    terminals.write(ctx.args().terminalId("terminalId"), ctx.requireWorkspace(),
                            ctx.args().requiredString("data"));
                    return null;
                });

        commands.register(
                CommandDescriptor.of("terminal.resize", "Terminal", "Resize Terminal") .withArguments(new dev.forge.core.command.Argument("terminalId", "string", "terminalId")).workspaceScoped(),
                ctx -> {
                    terminals.resize(ctx.args().terminalId("terminalId"), ctx.requireWorkspace(),
                            ctx.args().integer("columns", 80), ctx.args().integer("rows", 24));
                    return null;
                });

        commands.register(
                CommandDescriptor.of("terminal.kill", "Terminal", "Kill Terminal") .withArguments(new dev.forge.core.command.Argument("terminalId", "string", "terminalId"))
                        .workspaceScoped().asSensitive(),
                ctx -> {
                    terminals.kill(ctx.args().terminalId("terminalId"), ctx.requireWorkspace());
                    return null;
                });

        queries.register(
                QueryDescriptor.of("terminal.list", "Terminals open in this workspace").workspaceScoped(),
                (ctx, args) -> terminals.list(ctx.requireWorkspace()));

        queries.register(
                QueryDescriptor.of("terminal.scrollback", "Recent output for one terminal").workspaceScoped(),
                (ctx, args) -> terminals.scrollback(args.terminalId("terminalId"), ctx.requireWorkspace()));

        contributions.addKeybinding(dev.forge.core.contrib.Keybinding.of("ctrl+`", "workbench.newTerminal", null));
        contributions.addMenuItem(dev.forge.core.contrib.MenuItem.of(
                ContributionRegistry.MENU_VIEW, "workbench.newTerminal", "New Terminal", "panel", 10));
    }
}
