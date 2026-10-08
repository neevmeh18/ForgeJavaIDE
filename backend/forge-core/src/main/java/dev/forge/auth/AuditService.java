package dev.forge.auth;

import java.time.Instant;
import java.util.List;

/**
 * Service contract for the audit trail.
 *
 * <p>Every command execution and authorisation decision may be recorded as an
 * {@link AuditEntry}. Implementations may store entries in memory, on disk, or
 * forward them to an external audit system.
 */
public interface AuditService {

    /**
     * Records a single audit entry. Must be thread-safe and fast — this is on the
     * hot path of every command execution.
     */
    void record(AuditEntry entry);

    /**
     * Returns all audit entries by a specific user, most recent first.
     */
    List<AuditEntry> queryByUser(dev.forge.core.Ids.UserId userId);

    /**
     * Returns all audit entries for a specific action type, most recent first.
     */
    List<AuditEntry> queryByAction(String action);

    /**
     * Returns all audit entries after the given timestamp, most recent first.
     */
    List<AuditEntry> querySince(Instant since);

    /**
     * Returns up to the given number of most recent audit entries.
     */
    List<AuditEntry> all(int limit);
}