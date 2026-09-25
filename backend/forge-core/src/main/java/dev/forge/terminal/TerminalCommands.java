package dev.forge.terminal;

import dev.forge.core.command.CommandDescriptor;
import dev.forge.core.command.CommandRegistry;
import dev.forge.core.contrib.ContributionRegistry;
import dev.forge.core.query.QueryRegistry;
import dev.forge.core.query.QueryRegistry.QueryDescriptor;

/**
 * {@code terminal.*} commands and queries.
 *
 * <p>All marked sensitive: starting a process is the highest-privilege operation the framework
 * exposes, so no extension may quietly take over the id, and every call re-derives the
 * workspace from the authenticated context.
 */
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
                CommandDescriptor.of("terminal.write", "Terminal", "Send Terminal Input") .withArguments(new CommandDescriptor.Argument("terminalId", "string", "terminalId"), new CommandDescriptor.Argument("data", "string", "data"))
                        .workspaceScoped().asSensitive(),
                ctx -> {
                    terminals.write(ctx.args().terminalId("terminalId"), ctx.requireWorkspace(),
                            ctx.args().requiredString("data"));
                    return null;
                });

        commands.register(
                CommandDescriptor.of("terminal.noteCommand", "Terminal", "Track Terminal Command")
                        .withArguments(new CommandDescriptor.Argument("terminalId", "string", "terminalId"),
                                new CommandDescriptor.Argument("command", "string", "command"))
                        .workspaceScoped().asSensitive(),
                ctx -> {
                    terminals.noteCommand(ctx.args().terminalId("terminalId"), ctx.requireWorkspace(),
                            ctx.requireUser(), ctx.sessionId(), ctx.args().requiredString("command"));
                    return null;
                });

        commands.register(
                CommandDescriptor.of("terminal.resize", "Terminal", "Resize Terminal") .withArguments(new CommandDescriptor.Argument("terminalId", "string", "terminalId")).workspaceScoped(),
                ctx -> {
                    terminals.resize(ctx.args().terminalId("terminalId"), ctx.requireWorkspace(),
                            ctx.args().integer("columns", 80), ctx.args().integer("rows", 24));
                    return null;
                });

        commands.register(
                CommandDescriptor.of("terminal.kill", "Terminal", "Kill Terminal") .withArguments(new CommandDescriptor.Argument("terminalId", "string", "terminalId"))
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

        queries.register(
                QueryDescriptor.of("terminal.errors", "Recent terminal command failures"),
                (ctx, args) -> {
                    ctx.requireUser();
                    return terminals.errors();
                });

        contributions.addKeybinding(ContributionRegistry.Keybinding.of("ctrl+`", "workbench.newTerminal", null));
        contributions.addMenuItem(ContributionRegistry.MenuItem.of(
                ContributionRegistry.MENU_VIEW, "workbench.newTerminal", "New Terminal", "panel", 10));
    }
}
