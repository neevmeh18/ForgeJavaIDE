package dev.forge.tasks;

import dev.forge.core.command.CommandDescriptor;
import dev.forge.core.command.CommandRegistry;
import dev.forge.core.contrib.ContributionRegistry;
import dev.forge.core.query.QueryRegistry;
import dev.forge.core.query.QueryRegistry.QueryDescriptor;

/** {@code task.*} commands and queries. */
public final class TaskCommands {

    private final TaskService tasks;

    public TaskCommands(TaskService tasks) {
        this.tasks = tasks;
    }

    public void register(CommandRegistry commands, QueryRegistry queries, ContributionRegistry contributions) {
        commands.register(
                CommandDescriptor.of("task.run", "Tasks", "Run Task") .withArguments(new CommandDescriptor.Argument("taskId", "string", "taskId"))
                        .describedAs("Runs a task defined by the workspace or a provider")
                        .workspaceScoped().asSensitive(),
                ctx -> tasks.run(ctx.requireWorkspace(), ctx.args().requiredString("taskId")));

        commands.register(
                CommandDescriptor.of("task.cancel", "Tasks", "Cancel Task") .withArguments(new CommandDescriptor.Argument("executionId", "string", "executionId")).workspaceScoped().asSensitive(),
                ctx -> {
                    tasks.cancel(ctx.args().taskExecutionId("executionId"), ctx.requireWorkspace());
                    return null;
                });

        queries.register(
                QueryDescriptor.of("task.available", "Tasks offered for this workspace").workspaceScoped(),
                (ctx, args) -> tasks.available(ctx.requireWorkspace()));

        queries.register(
                QueryDescriptor.of("task.executions", "Recent task runs").workspaceScoped(),
                (ctx, args) -> tasks.executions(ctx.requireWorkspace()));

        contributions.addMenuItem(ContributionRegistry.MenuItem.of(
                ContributionRegistry.MENU_VIEW, "workbench.runTask", "Run Task…", "panel", 20));
    }
}
