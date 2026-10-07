package dev.forge.infra;

import dev.forge.auth.AuditEntry;
import dev.forge.auth.AuditService;
import dev.forge.core.Ids.UserId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * In-memory audit log backed by a bounded ring buffer.
 *
 * <p>Entries are kept in a {@link ConcurrentLinkedDeque} and trimmed to the configured
 * maximum. This is designed for fast writes on the command hot path — no I/O, no locks
 * on the write side — and for simple linear scans on the read side.
 *
 * <p>Reads are not threadsafe in the strict sense (a concurrent write may shift entries),
 * but the deque is snapshot-safe for reads.
 */
public final class InMemoryAuditService implements AuditService {

    private final ConcurrentLinkedDeque<AuditEntry> entries = new ConcurrentLinkedDeque<>();
    private final int maxEntries;

    public InMemoryAuditService(int maxEntries) {
        this.maxEntries = Math.max(100, Math.min(maxEntries, 1_000_000));
    }

    @Override
    public void record(AuditEntry entry) {
        entries.addFirst(entry);
        while (entries.size() > maxEntries) {
            entries.pollLast();
        }
    }

    @Override
    public List<AuditEntry> queryByUser(UserId userId) {
        if (userId == null) {
            return List.of();
        }
        List<AuditEntry> result = new ArrayList<>();
        for (AuditEntry entry : entries) {
            if (userId.equals(entry.userId())) {
                result.add(entry);
            }
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public List<AuditEntry> queryByAction(String action) {
        if (action == null || action.isBlank()) {
            return List.of();
        }
        List<AuditEntry> result = new ArrayList<>();
        for (AuditEntry entry : entries) {
            if (action.equals(entry.action())) {
                result.add(entry);
            }
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public List<AuditEntry> querySince(Instant since) {
        if (since == null) {
            return all(Integer.MAX_VALUE);
        }
        List<AuditEntry> result = new ArrayList<>();
        for (AuditEntry entry : entries) {
            if (!entry.timestamp().isBefore(since)) {
                result.add(entry);
            }
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public List<AuditEntry> all(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<AuditEntry> result = new ArrayList<>();
        int count = 0;
        for (AuditEntry entry : entries) {
            if (count >= limit) {
                break;
            }
            result.add(entry);
            count++;
        }
        return Collections.unmodifiableList(result);
    }
}