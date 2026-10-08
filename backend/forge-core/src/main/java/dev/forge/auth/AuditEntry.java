package dev.forge.auth;

import dev.forge.core.Ids.SessionId;
import dev.forge.core.Ids.UserId;
import java.time.Instant;

/**
 * A single audit trail entry recording a command or query execution.
 *
 * <p>Audit entries are immutable once created. The {@link AuditService} stores and retrieves
 * them in chronological order. The {@code detail} field is truncated to 512 characters and
 * must never contain credentials, token values, or full stack traces.
 *
 * @param timestamp  when the action occurred
 * @param userId     the authenticated user who performed the action, if any
 * @param sessionId  the session that performed the action, if any
 * @param action     the command or query id
 * @param resource   the target resource identifier, if any
 * @param outcome    whether the action succeeded, failed, or was denied
 * @param detail     a short, non-sensitive description of the result
 */
public record AuditEntry(
        Instant timestamp,
        UserId userId,
        SessionId sessionId,
        String action,
        String resource,
        Outcome outcome,
        String detail) {

    public enum Outcome {
        SUCCESS,
        FAILURE,
        FORBIDDEN
    }

    private static final int MAX_DETAIL_LENGTH = 512;

    public AuditEntry {
        timestamp = timestamp == null ? Instant.now() : timestamp;
        action = action == null ? "" : action;
        resource = resource == null ? "" : resource;
        detail = detail == null ? "" : (detail.length() > MAX_DETAIL_LENGTH ? detail.substring(0, MAX_DETAIL_LENGTH) : detail);
    }

    /**
     * Convenience factory for a successful audit entry with an auto-timestamp.
     */
    public static AuditEntry ofSuccess(UserId userId, SessionId sessionId,
                                       String action, String resource, String detail) {
        return new AuditEntry(Instant.now(), userId, sessionId, action, resource, Outcome.SUCCESS, detail);
    }

    /**
     * Convenience factory for a failed audit entry with an auto-timestamp.
     */
    public static AuditEntry ofFailure(UserId userId, SessionId sessionId,
                                       String action, String resource, String detail) {
        return new AuditEntry(Instant.now(), userId, sessionId, action, resource, Outcome.FAILURE, detail);
    }

    /**
     * Convenience factory for a denied audit entry with an auto-timestamp.
     */
    public static AuditEntry ofForbidden(UserId userId, SessionId sessionId,
                                          String action, String resource, String detail) {
        return new AuditEntry(Instant.now(), userId, sessionId, action, resource, Outcome.FORBIDDEN, detail);
    }
}