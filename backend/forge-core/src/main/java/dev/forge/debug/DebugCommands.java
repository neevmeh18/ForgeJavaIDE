package dev.forge.debug;

import dev.forge.core.command.CommandDescriptor;
import dev.forge.core.command.CommandRegistry;
import dev.forge.core.contrib.ContributionRegistry;
import dev.forge.core.query.QueryRegistry;
import dev.forge.core.query.QueryDescriptor;
import dev.forge.debug.DebugConfiguration;
import java.util.Optional;








public final class DebugCommands {

    private final DebugService debug;

    public DebugCommands(DebugService debug) {
        this.debug = debug;
    }

    public void register(CommandRegistry commands, QueryRegistry queries, ContributionRegistry contributions) {
        dev.forge.core.command.Availability requiresSession = ctx ->
                debug.hasActiveSession(ctx.requireWorkspace())
                        ? Optional.empty()
                        : Optional.of("No active debug session");

        commands.register(
                CommandDescriptor.of("debug.start", "Debug", "Start Debugging") .withArguments(new dev.forge.core.command.Argument("type", "string", "type"))
                        .describedAs("Starts a debug session using a registered adapter")
                        .workspaceScoped().asSensitive(),
                ctx -> debug.start(ctx.requireWorkspace(), new DebugConfiguration(
                        ctx.args().string("name").orElse("Debug"),
                        ctx.args().requiredString("type"),
                        ctx.args().string("request").orElse("launch"),
                        ctx.args().nested("options").values())));

        commands.register(
                CommandDescriptor.of("debug.stop", "Debug", "Stop Debugging") .withArguments(new dev.forge.core.command.Argument("debugSessionId", "string", "debugSessionId"))
                        .workspaceScoped().asSensitive().availableWhen(requiresSession),
                ctx -> {
                    debug.stop(ctx.args().debugSessionId("debugSessionId"), ctx.requireWorkspace());
                    return null;
                });

        commands.register(
                CommandDescriptor.of("debug.continue", "Debug", "Continue") .withArguments(new dev.forge.core.command.Argument("debugSessionId", "string", "debugSessionId"))
                        .workspaceScoped().availableWhen(requiresSession),
                ctx -> {
                    debug.resume(ctx.args().debugSessionId("debugSessionId"), ctx.requireWorkspace(),
                            ctx.args().integer("threadId", 0));
                    return null;
                });

        commands.register(
                CommandDescriptor.of("debug.pause", "Debug", "Pause") .withArguments(new dev.forge.core.command.Argument("debugSessionId", "string", "debugSessionId"))
                        .workspaceScoped().availableWhen(requiresSession),
                ctx -> {
                    debug.pause(ctx.args().debugSessionId("debugSessionId"), ctx.requireWorkspace(),
                            ctx.args().integer("threadId", 0));
                    return null;
                });

        commands.register(
                CommandDescriptor.of("debug.step", "Debug", "Step") .withArguments(new dev.forge.core.command.Argument("debugSessionId", "string", "debugSessionId"))
                        .describedAs("Steps over, into or out of the current statement")
                        .workspaceScoped().availableWhen(requiresSession),
                ctx -> {
                    debug.step(ctx.args().debugSessionId("debugSessionId"), ctx.requireWorkspace(),
                            ctx.args().string("kind").orElse("over"), ctx.args().integer("threadId", 0));
                    return null;
                });

        commands.register(
                CommandDescriptor.of("debug.toggleBreakpoint", "Debug", "Toggle Breakpoint") .withArguments(new dev.forge.core.command.Argument("path", "string", "path"), new dev.forge.core.command.Argument("line", "number", "line")).workspaceScoped(),
                ctx -> debug.toggleBreakpoint(ctx.requireWorkspace(), ctx.args().requiredString("path"),
                        ctx.args().integer("line", 1), ctx.args().string("condition").orElse(null)));

        queries.register(
                QueryDescriptor.of("debug.sessions", "Debug sessions in this workspace").workspaceScoped(),
                (ctx, args) -> debug.sessions(ctx.requireWorkspace()));

        queries.register(
                QueryDescriptor.of("debug.breakpoints", "Breakpoints set in this workspace").workspaceScoped(),
                (ctx, args) -> debug.breakpoints(ctx.requireWorkspace()));

        queries.register(
                QueryDescriptor.of("debug.stackTrace", "Stack frames for a stopped thread").workspaceScoped(),
                (ctx, args) -> debug.stackTrace(args.debugSessionId("debugSessionId"), ctx.requireWorkspace(),
                        args.integer("threadId", 0)));

        queries.register(
                QueryDescriptor.of("debug.variables", "Variables in a stack frame").workspaceScoped(),
                (ctx, args) -> debug.variables(args.debugSessionId("debugSessionId"), ctx.requireWorkspace(),
                        args.integer("frameId", 0), args.integer("reference", 0)));

        queries.register(
                QueryDescriptor.of("debug.adapters", "Debug adapter types available"),
                (ctx, args) -> debug.adapterTypes());

        contributions.addKeybinding(dev.forge.core.contrib.Keybinding.of("f5", "workbench.startDebug", null));
        contributions.addView(dev.forge.core.contrib.View.of("debug", "Run and Debug", "sidebar", "bug", 40));
    }
}
