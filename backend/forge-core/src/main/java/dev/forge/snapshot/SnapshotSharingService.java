package dev.forge.snapshot;

import dev.forge.core.ForgeException;
import dev.forge.core.Ids;
import dev.forge.core.Ids.SessionId;
import dev.forge.core.Ids.UserId;
import dev.forge.core.Ids.WorkspaceId;
import dev.forge.core.event.EventBus;
import dev.forge.workspace.WorkspaceService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Issues short-lived links for importing workspace snapshots. */
public final class SnapshotSharingService {

    public record ShareInfo(
            String id,
            String snapshotId,
            WorkspaceId sourceWorkspaceId,
            String snapshotName,
            Instant createdAt,
            Instant expiresAt) {
    }

    public record IssuedShare(ShareInfo share, String token) {
    }

    public record ImportedSnapshot(
            String snapshotId,
            String name,
            WorkspaceId sourceWorkspaceId,
            WorkspaceId targetWorkspaceId) {
    }

    private record Entry(ShareInfo info, UserId ownerId, String tokenHash) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, Entry> byTokenHash = new ConcurrentHashMap<>();
    private final Map<String, Entry> byId = new ConcurrentHashMap<>();
    private final SnapshotStore store;
    private final WorkspaceService workspaces;
    private final EventBus events;

    public SnapshotSharingService(SnapshotStore store, WorkspaceService workspaces, EventBus events) {
        this.store = store;
        this.workspaces = workspaces;
        this.events = events;
    }

    public IssuedShare issue(UserId owner, WorkspaceId workspace, String snapshotId, int validDays) {
        if (validDays < 1 || validDays > 30) {
            throw ForgeException.invalidArgument("Share duration must be between 1 and 30 days");
        }
        Snapshot snapshot = requireSource(store.find(owner, snapshotId), workspace);
        if (snapshot.expired(Instant.now())) {
            throw ForgeException.notFound("Unknown snapshot: " + snapshotId)
                    .with("snapshotId", snapshotId);
        }
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        java.util.Arrays.fill(random, (byte) 0);
        Instant now = Instant.now();
        ShareInfo info = new ShareInfo(Ids.random("share"), snapshot.id(), workspace,
                snapshot.name(), now, now.plus(validDays, ChronoUnit.DAYS));
        Entry entry = new Entry(info, owner, hash(token));
        byTokenHash.put(entry.tokenHash(), entry);
        byId.put(info.id(), entry);
        events.publish(new SnapshotEvents.ShareCreated(workspace, info.id(), snapshot.id()));
        return new IssuedShare(info, token);
    }

    public List<ShareInfo> list(UserId owner, WorkspaceId workspace) {
        evictExpired();
        return byId.values().stream()
                .filter(entry -> entry.ownerId().equals(owner))
                .map(Entry::info)
                .filter(info -> info.sourceWorkspaceId().equals(workspace))
                .sorted(Comparator.comparing(ShareInfo::createdAt).reversed())
                .toList();
    }

    public ShareInfo preview(String token) {
        return require(token).info();
    }

    public ImportedSnapshot importSnapshot(String token, UserId recipient, SessionId session,
                                           WorkspaceId targetWorkspace) {
        Entry entry = require(token);
        Snapshot snapshot = store.find(entry.ownerId(), entry.info().snapshotId());
        workspaces.open(entry.info().sourceWorkspaceId(), session);
        store.restore(entry.ownerId(), snapshot.id(), targetWorkspace);
        events.publish(new SnapshotEvents.SharedSnapshotImported(
                targetWorkspace, entry.info().id(), snapshot.id(), recipient));
        return new ImportedSnapshot(snapshot.id(), snapshot.name(),
                entry.info().sourceWorkspaceId(), targetWorkspace);
    }

    public void revoke(UserId owner, WorkspaceId workspace, String shareId) {
        Entry entry = byId.get(shareId);
        if (entry == null || !entry.ownerId().equals(owner)
                || !entry.info().sourceWorkspaceId().equals(workspace)) {
            throw ForgeException.notFound("Unknown snapshot share: " + shareId)
                    .with("shareId", shareId);
        }
        byId.remove(shareId, entry);
        byTokenHash.remove(entry.tokenHash(), entry);
        events.publish(new SnapshotEvents.ShareRevoked(workspace, shareId));
    }

    private Entry require(String token) {
        if (token == null || token.isBlank()) {
            throw ForgeException.invalidArgument("Missing snapshot share token");
        }
        Entry entry = byTokenHash.get(hash(token));
        if (entry == null || !entry.info().expiresAt().isAfter(Instant.now())) {
            if (entry != null) {
                remove(entry);
            }
            throw ForgeException.notFound("Snapshot share is unavailable");
        }
        return entry;
    }

    private void evictExpired() {
        Instant now = Instant.now();
        List.copyOf(byId.values()).stream()
                .filter(entry -> !entry.info().expiresAt().isAfter(now))
                .forEach(this::remove);
    }

    private void remove(Entry entry) {
        byId.remove(entry.info().id(), entry);
        byTokenHash.remove(entry.tokenHash(), entry);
    }

    private static Snapshot requireSource(Snapshot snapshot, WorkspaceId workspace) {
        if (!snapshot.sourceWorkspaceId().equals(workspace)) {
            throw ForgeException.notFound("Unknown snapshot: " + snapshot.id())
                    .with("snapshotId", snapshot.id());
        }
        return snapshot;
    }

    private static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw ForgeException.internal("SHA-256 unavailable", e);
        }
    }
}
