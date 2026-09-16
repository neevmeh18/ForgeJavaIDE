package dev.forge.workspace;

import dev.forge.core.Ids.WorkspaceId;
import dev.forge.core.command.CommandDescriptor;
import dev.forge.core.command.CommandRegistry;
import dev.forge.core.contrib.ContributionRegistry;
import dev.forge.core.query.QueryRegistry;
import dev.forge.core.query.QueryRegistry.QueryDescriptor;
import java.util.LinkedHashMap;
import java.util.Map;

/** {@code workspace.*} commands and queries. */
public final class WorkspaceCommands {

    private final WorkspaceService workspaces;

    public WorkspaceCommands(WorkspaceService workspaces) {
        this.workspaces = workspaces;
    }

    public void register(CommandRegistry commands, QueryRegistry queries, ContributionRegistry contributions) {
        commands.register(
                CommandDescriptor.of("workspace.open", "Workspace", "Open Workspace") .withArguments(new CommandDescriptor.Argument("workspaceId", "string", "workspaceId"))
                        .describedAs("Opens a workspace and attaches the current session to it")
                        .asSensitive(),
                ctx -> workspaces.open(WorkspaceId.of(ctx.args().requiredString("workspaceId")), ctx.sessionId()));

        commands.register(
                CommandDescriptor.of("workspace.close", "Workspace", "Close Workspace")
                        .workspaceScoped().asSensitive(),
                ctx -> {
                    workspaces.release(ctx.requireWorkspace(), ctx.sessionId());
                    return null;
                });

        commands.register(
                CommandDescriptor.of("workspace.reload", "Workspace", "Reload Workspace")
                        .workspaceScoped(),
                ctx -> workspaces.reload(ctx.requireWorkspace()));

        commands.register(
                CommandDescriptor.of("workspace.create", "Workspace", "New Workspace") .withArguments(new CommandDescriptor.Argument("name", "string", "name")).asSensitive(),
                ctx -> {
                    Map<String, String> options = new LinkedHashMap<>();
                    ctx.args().nested("options").values()
                            .forEach((key, value) -> options.put(key, String.valueOf(value)));
                    return workspaces.create(ctx.args().requiredString("name"),
                            ctx.args().string("scheme").orElse("local"), options);
                });

        queries.register(
                QueryDescriptor.of("workspace.available", "Workspaces offered by every provider"),
                (ctx, args) -> workspaces.available());

        queries.register(
                QueryDescriptor.of("workspace.opened", "Workspaces currently open"),
                (ctx, args) -> workspaces.opened());

        queries.register(
                QueryDescriptor.of("workspace.current", "The workspace bound to this request").workspaceScoped(),
                (ctx, args) -> workspaces.require(ctx.requireWorkspace(), ctx.sessionId()));

        queries.register(
                QueryDescriptor.of("workspace.sessions", "Sessions attached to the current workspace")
                        .workspaceScoped(),
                (ctx, args) -> workspaces.sessions(ctx.requireWorkspace()).stream()
                        .map(dev.forge.core.Ids.SessionId::value).sorted().toList());

        contributions.addMenuItem(ContributionRegistry.MenuItem.of(
                ContributionRegistry.MENU_FILE, "workbench.openWorkspace", "Open Workspace…", "workspace", 1));
        contributions.addMenuItem(ContributionRegistry.MenuItem.of(
                ContributionRegistry.MENU_FILE, "workspace.close", "Close Workspace", "workspace", 2));
    }
}
