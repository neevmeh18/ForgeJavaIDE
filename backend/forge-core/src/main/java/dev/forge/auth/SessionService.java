package dev.forge.auth;

import dev.forge.core.ForgeException;
import dev.forge.core.Ids;
import dev.forge.core.Ids.SessionId;
import dev.forge.core.Lifecycle;
import dev.forge.core.Log;
import dev.forge.core.event.EventBus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Issues, validates and revokes sessions.
 *
 * <p>Tokens are 256 bits from {@link SecureRandom} and are returned to the client exactly once.
 * Only a SHA-256 hash is retained, so a memory dump or a future persisted store never yields a
 * usable credential, and lookup by hash avoids comparing secrets at all.
 *
 * <p>Sessions expire on an idle timeout that slides on use and on a hard maximum lifetime, so a
 * forgotten browser tab cannot hold a session open indefinitely.
 */
public final class SessionService implements Lifecycle.Component {

    private static final Log log = Log.of(SessionService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Returned once, at login. The raw token exists only in this value and in the response. */
    public record Issued(Session session, String token, Instant expiresAt) {
    }

    private record Entry(Session session) {
    }

    private final Map<String, Entry> byTokenHash = new ConcurrentHashMap<>();
    private final Map<SessionId, String> hashBySession = new ConcurrentHashMap<>();
    private final java.util.concurrent.ScheduledExecutorService sweeper =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "forge-session-sweeper");
                thread.setDaemon(true);
                return thread;
            });
    private final EventBus events;
    private final Duration idleTimeout;
    private final Duration maxLifetime;

    public SessionService(EventBus events, Duration idleTimeout, Duration maxLifetime) {
        this.events = events;
        this.idleTimeout = idleTimeout;
        this.maxLifetime = maxLifetime;
    }

    @Override
    public void start() {
        // Without a sweep, an abandoned session would sit in memory until someone happened to
        // present its token. The interval only has to be short relative to the idle timeout.
        sweeper.scheduleWithFixedDelay(this::evictExpired, 5, 5, java.util.concurrent.TimeUnit.MINUTES);
        log.with("idleTimeoutMinutes", idleTimeout.toMinutes()).info("Session service ready");
    }

    @Override
    public void dispose() {
        sweeper.shutdownNow();
        byTokenHash.clear();
        hashBySession.clear();
    }

    public Issued issue(User user, String clientInfo) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        java.util.Arrays.fill(raw, (byte) 0);

        Instant now = Instant.now();
        Session session = new Session(SessionId.of(Ids.random("sess")), user.id(), now, now,
                now.plus(idleTimeout), sanitize(clientInfo));
        String hash = hash(token);
        byTokenHash.put(hash, new Entry(session));
        hashBySession.put(session.id(), hash);

        log.with("userId", user.id()).with("sessionId", session.id()).info("Session issued");
        events.publish(new AuthEvents.SessionStarted(session.id(), user.id()));
        return new Issued(session, token, session.expiresAt());
    }

    /**
     * Validates a bearer token. Returns empty for unknown, expired or revoked tokens — the
     * caller must not be told which, and the transport reports a single {@code UNAUTHORIZED}.
     */
    public Optional<Session> authenticate(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String hash = hash(token);
        Entry entry = byTokenHash.get(hash);
        if (entry == null) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        Session session = entry.session();
        if (session.isExpired(now) || now.isAfter(session.createdAt().plus(maxLifetime))) {
            revoke(session.id(), "expired");
            return Optional.empty();
        }
        Session refreshed = session.seen(now, now.plus(idleTimeout));
        byTokenHash.put(hash, new Entry(refreshed));
        return Optional.of(refreshed);
    }

    public Optional<Session> find(SessionId id) {
        String hash = hashBySession.get(id);
        return hash == null ? Optional.empty() : Optional.ofNullable(byTokenHash.get(hash)).map(Entry::session);
    }

    public void revoke(SessionId id, String reason) {
        String hash = hashBySession.remove(id);
        if (hash == null) {
            return;
        }
        Entry entry = byTokenHash.remove(hash);
        if (entry != null) {
            log.with("sessionId", id).with("reason", reason).info("Session revoked");
            events.publish(new AuthEvents.SessionEnded(id, entry.session().userId(), reason));
        }
    }

    /** Drops expired sessions. Cheap enough to run on a timer from the application bootstrap. */
    public void evictExpired() {
        Instant now = Instant.now();
        List.copyOf(byTokenHash.entrySet()).forEach(entry -> {
            Session session = entry.getValue().session();
            if (session.isExpired(now) || now.isAfter(session.createdAt().plus(maxLifetime))) {
                revoke(session.id(), "expired");
            }
        });
    }

    public int activeSessions() {
        return byTokenHash.size();
    }

    private static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Base64.getEncoder().encodeToString(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw ForgeException.internal("SHA-256 unavailable", e);
        }
    }

    /** Client-supplied strings end up in logs; keep them short and free of control characters. */
    private static String sanitize(String clientInfo) {
        if (clientInfo == null) {
            return "";
        }
        String trimmed = clientInfo.length() > 120 ? clientInfo.substring(0, 120) : clientInfo;
        return trimmed.replaceAll("[\\p{Cntrl}]", "");
    }
}
