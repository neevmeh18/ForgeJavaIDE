package dev.forge.auth;

import dev.forge.core.ForgeException;
import dev.forge.core.Ids;
import dev.forge.core.SessionId;
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











public final class SessionService implements dev.forge.core.Component {

    private static final Log log = Log.of(SessionService.class);
    private static final SecureRandom RANDOM = new SecureRandom();




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
    private final int maxSessions;
    private final Object lock = new Object();

    public SessionService(EventBus events, Duration idleTimeout, Duration maxLifetime, int maxSessions) {
        this.events = events;
        this.idleTimeout = idleTimeout;
        this.maxLifetime = maxLifetime;
        this.maxSessions = maxSessions;
    }

    @Override
    public void start() {


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

        Session session;
        synchronized (lock) {
            evictExpiredLocked(Instant.now());
            if (byTokenHash.size() >= maxSessions) {
                throw ForgeException.unavailable("Too many active sessions");
            }
            Instant now = Instant.now();
            session = new Session(SessionId.of(Ids.random("sess")), user.id(), now, now,
                    now.plus(idleTimeout), sanitize(clientInfo));
            String hash = hash(token);
            byTokenHash.put(hash, new Entry(session));
            hashBySession.put(session.id(), hash);
        }

        log.with("userId", user.id()).with("sessionId", session.id()).info("Session issued");
        events.publish(new dev.forge.auth.SessionStarted(session.id(), user.id()));
        return new Issued(session, token, session.expiresAt());
    }





    public Optional<Session> authenticate(String token) {
        if (token == null || token.isBlank() || token.length() > 256) {
            return Optional.empty();
        }
        String hash = hash(token);
        synchronized (lock) {
            Entry entry = byTokenHash.get(hash);
            if (entry == null) {
                return Optional.empty();
            }
            Instant now = Instant.now();
            Session session = entry.session();
            if (session.isExpired(now) || now.isAfter(session.createdAt().plus(maxLifetime))) {
                revokeLocked(session.id(), "expired");
                return Optional.empty();
            }
            if (!hash.equals(hashBySession.get(session.id()))) {
                return Optional.empty();
            }
            Session refreshed = session.seen(now, now.plus(idleTimeout));
            byTokenHash.put(hash, new Entry(refreshed));
            return Optional.of(refreshed);
        }
    }

    public Optional<Session> find(SessionId id) {
        if (id == null) return Optional.empty();
        synchronized (lock) {
            String hash = hashBySession.get(id);
            if (hash == null) return Optional.empty();
            Entry entry = byTokenHash.get(hash);
            if (entry == null) return Optional.empty();
            Instant now = Instant.now();
            Session session = entry.session();
            if (session.isExpired(now) || now.isAfter(session.createdAt().plus(maxLifetime))) {
                revokeLocked(id, "expired");
                return Optional.empty();
            }
            return Optional.of(session);
        }
    }

    public void revoke(SessionId id, String reason) {
        synchronized (lock) {
            revokeLocked(id, reason);
        }
    }

    private void revokeLocked(SessionId id, String reason) {
        String hash = hashBySession.remove(id);
        if (hash == null) {
            return;
        }
        Entry entry = byTokenHash.remove(hash);
        if (entry != null) {
            log.with("sessionId", id).with("reason", sanitize(reason)).info("Session revoked");
            events.publish(new dev.forge.auth.SessionEnded(id, entry.session().userId(), sanitize(reason)));
        }
    }


    public void evictExpired() {
        synchronized (lock) {
            evictExpiredLocked(Instant.now());
        }
    }

    private void evictExpiredLocked(Instant now) {
        List.copyOf(byTokenHash.values()).forEach(entry -> {
            Session session = entry.session();
            if (session.isExpired(now) || now.isAfter(session.createdAt().plus(maxLifetime))) {
                revokeLocked(session.id(), "expired");
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


    private static String sanitize(String clientInfo) {
        if (clientInfo == null) {
            return "";
        }
        String trimmed = clientInfo.length() > 120 ? clientInfo.substring(0, 120) : clientInfo;
        return trimmed.replaceAll("[\\p{Cntrl}]", "");
    }
}
