package dev.forge.auth;

import dev.forge.core.ForgeException;
import dev.forge.core.query.QueryRegistry;
import dev.forge.core.query.QueryRegistry.QueryDescriptor;
import java.time.Instant;
import java.util.List;

/**
 * {@code audit.*} queries.
 *
 * <p>Provides the audit log query used to inspect command execution history.
 * The query is RBAC-gated via {@link Authorizer#COMMAND_PERMISSIONS} to require
 * {@link PermissionConstants#AUDIT_VIEW}.
 */
public final class AuditCommands {

    /**
     * A view-safe representation of an audit entry, preventing detail leakage.
     */
    public record AuditEntryView(
            String timestamp,
            String userId,
            String action,
            String resource,
            String outcome,
            String detail) {
    }

    private final AuditService audit;

    public AuditCommands(AuditService audit) {
        this.audit = audit;
    }

    public void register(QueryRegistry queries) {
        queries.register(
                QueryDescriptor.of("audit.log", "View the command audit trail")
                        .workspaceScoped(),
                (ctx, args) -> {
                    List<AuditEntry> entries;
                    String user = args.string("user").orElse(null);
                    String action = args.string("action").orElse(null);
                    Long since = args.longInteger("since").orElse(null);
                    int limit = args.integer("limit", 100);

                    if (user != null) {
                        entries = audit.queryByUser(dev.forge.core.Ids.UserId.of(user));
                    } else if (action != null) {
                        entries = audit.queryByAction(action);
                    } else if (since != null) {
                        entries = audit.querySince(Instant.ofEpochSecond(since));
                    } else {
                        entries = audit.all(limit);
                    }

                    return entries.stream()
                            .limit(Math.min(limit, 1000))
                            .map(e -> new AuditEntryView(
                                    e.timestamp().toString(),
                                    e.userId() != null ? e.userId().value() : "",
                                    e.action(),
                                    e.resource(),
                                    e.outcome().name(),
                                    e.detail()))
                            .toList();
                });
    }
}