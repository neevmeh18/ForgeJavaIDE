package dev.forge.tasklog;

import dev.forge.core.contrib.ContributionRegistry;
import dev.forge.core.query.QueryRegistry;
import dev.forge.core.query.QueryRegistry.QueryDescriptor;

public final class TaskLogQueries {
    private final TaskLogService logs;

    public TaskLogQueries(TaskLogService logs) {
        this.logs = logs;
    }

    public void register(QueryRegistry queries, ContributionRegistry contributions) {
        queries.register(
                QueryDescriptor.of("taskLog.executions", "Task runs with structured logs").workspaceScoped(),
                (ctx, args) -> logs.summaries(ctx.requireWorkspace()));

        queries.register(
                QueryDescriptor.of("taskLog.records", "Structured records for a task run").workspaceScoped(),
                (ctx, args) -> logs.records(ctx.requireWorkspace(), args.taskExecutionId("executionId")));

        contributions.addView(ContributionRegistry.View.of(
                "taskLogs", "Task Logs", "panel", "output", 40));
    }
}
