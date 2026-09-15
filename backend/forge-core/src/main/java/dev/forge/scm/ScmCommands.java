package dev.forge.scm;

import dev.forge.core.command.CommandDescriptor;
import dev.forge.core.command.CommandRegistry;
import dev.forge.core.contrib.ContributionRegistry;
import dev.forge.core.query.QueryRegistry;
import dev.forge.core.query.QueryRegistry.QueryDescriptor;

/**
 * {@code scm.*} commands and queries.
 *
 * <p>Network operations are commands because they are slow and cancellable; status and diffs
 * are queries because the source-control view reads them constantly.
 */
public final class ScmCommands {

    private final SourceControlService scm;

    public ScmCommands(SourceControlService scm) {
        this.scm = scm;
    }

    public void register(CommandRegistry commands, QueryRegistry queries, ContributionRegistry contributions) {
        commands.register(
                CommandDescriptor.of("scm.stage", "Source Control", "Stage Changes")
                        .workspaceScoped().asSensitive(),
                ctx -> {
                    scm.stage(ctx.requireWorkspace(), ctx.args().strings("paths"));
                    return null;
                });

        commands.register(
                CommandDescriptor.of("scm.unstage", "Source Control", "Unstage Changes")
                        .workspaceScoped().asSensitive(),
                ctx -> {
                    scm.unstage(ctx.requireWorkspace(), ctx.args().strings("paths"));
                    return null;
                });

        commands.register(
                CommandDescriptor.of("scm.discard", "Source Control", "Discard Changes")
                        .describedAs("Throws away working-tree changes; not undoable")
                        .workspaceScoped().asSensitive(),
                ctx -> {
                    scm.discard(ctx.requireWorkspace(), ctx.args().strings("paths"));
                    return null;
                });

        commands.register(
                CommandDescriptor.of("scm.commit", "Source Control", "Commit")
                        .workspaceScoped().asSensitive(),
                ctx -> scm.commit(ctx.requireWorkspace(), ctx.args().requiredString("message"),
                        ctx.args().bool("amend", false)));

        commands.register(
                CommandDescriptor.of("scm.checkout", "Source Control", "Checkout Branch")
                        .workspaceScoped().asSensitive(),
                ctx -> {
                    scm.checkout(ctx.requireWorkspace(), ctx.args().requiredString("branch"),
                            ctx.args().bool("create", false));
                    return null;
                });

        commands.register(
                CommandDescriptor.of("scm.fetch", "Source Control", "Fetch").workspaceScoped().asSensitive(),
                ctx -> {
                    scm.fetch(ctx.requireWorkspace());
                    return null;
                });

        commands.register(
                CommandDescriptor.of("scm.pull", "Source Control", "Pull").workspaceScoped().asSensitive(),
                ctx -> {
                    scm.pull(ctx.requireWorkspace());
                    return null;
                });

        commands.register(
                CommandDescriptor.of("scm.push", "Source Control", "Push").workspaceScoped().asSensitive(),
                ctx -> {
                    scm.push(ctx.requireWorkspace());
                    return null;
                });

        queries.register(
                QueryDescriptor.of("scm.status", "Working tree and branch status").workspaceScoped(),
                (ctx, args) -> scm.status(ctx.requireWorkspace()));

        queries.register(
                QueryDescriptor.of("scm.branches", "Branches in the repository").workspaceScoped(),
                (ctx, args) -> scm.branches(ctx.requireWorkspace()));

        queries.register(
                QueryDescriptor.of("scm.diff", "Unified diff for one path").workspaceScoped(),
                (ctx, args) -> scm.diff(ctx.requireWorkspace(), args.requiredString("path"),
                        args.bool("staged", false)));

        queries.register(
                QueryDescriptor.of("scm.history", "Recent commits").workspaceScoped(),
                (ctx, args) -> scm.history(ctx.requireWorkspace(), args.string("path").orElse(""),
                        args.integer("limit", 50)));

        contributions.addView(ContributionRegistry.View.of(
                "scm", "Source Control", "sidebar", "git-branch", 30));
    }
}
