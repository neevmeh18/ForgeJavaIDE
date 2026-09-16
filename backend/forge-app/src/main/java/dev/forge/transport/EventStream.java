package dev.forge.transport;

import dev.forge.core.ForgeException;
import dev.forge.core.Ids.SessionId;
import dev.forge.core.Lifecycle;
import dev.forge.core.Log;
import dev.forge.core.event.Event;
import dev.forge.core.event.EventBus;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;

/** Bounded, visibility-filtered server-sent event fan-out. */
public final class EventStream implements Lifecycle.Component {

    private static final Log log = Log.of(EventStream.class);
    private static final int QUEUE_CAPACITY = 2048;

    private record Frame(String type, String workspaceId, Object payload) { }

    public final class Client implements Lifecycle.Disposable {
        private final SessionId session;
        private final BlockingQueue<String> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        private final AtomicBoolean disposed = new AtomicBoolean();
        private volatile boolean overflowed;

        private Client(SessionId session) {
            this.session = session;
        }

        public String poll(Duration timeout) throws InterruptedException {
            if (overflowed) {
                overflowed = false;
                return "event: forge.resync\ndata: {\"type\":\"forge.resync\",\"workspaceId\":null,\"payload\":{}}\n\n";
            }
            if (disposed.get()) throw new InterruptedException("Event stream closed");
            String frame = queue.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
            synchronized (this) { if (frame != null) queuedChars = Math.max(0, queuedChars - frame.length()); }
            return frame;
        }

        private int queuedChars;

        private synchronized void offer(String frame) {
            if (disposed.get()) return;
            if (frame.length() > 1024 * 1024 || queuedChars + frame.length() > 2 * 1024 * 1024) {
                queue.clear(); queuedChars = 0; overflowed = true;
                return;
            }
            queuedChars += frame.length();
            if (!queue.offer(frame)) {
                // Never silently pretend delivery is reliable. Drop old data, then explicitly
                // tell the client to refresh authoritative state.
                queue.clear();
                queuedChars = frame.length();
                overflowed = true;
                queue.offer(frame);
            }
        }

        @Override
        public void dispose() {
            if (!disposed.compareAndSet(false, true)) {
                return;
            }
            queue.clear();
            queue.offer(": closed\n\n");
            synchronized (clientsBySession) {
            clients.remove(this);
            AtomicInteger count = clientsBySession.get(session);
            if (count != null && count.decrementAndGet() <= 0) {
                clientsBySession.remove(session, count);
            }
            }
        }
    }

    private final Set<Client> clients = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<SessionId, AtomicInteger> clientsBySession = new ConcurrentHashMap<>();
    private final EventBus events;
    private final Json json;
    private final BiPredicate<SessionId, Event> visibility;
    private final Lifecycle.Store subscriptions = new Lifecycle.Store();
    private final int maxClients;
    private final int maxClientsPerSession;

    public EventStream(EventBus events, Json json, BiPredicate<SessionId, Event> visibility,
                       int maxClients, int maxClientsPerSession) {
        this.events = events;
        this.json = json;
        this.visibility = visibility;
        this.maxClients = maxClients;
        this.maxClientsPerSession = maxClientsPerSession;
    }

    @Override
    public void start() {
        subscriptions.add(events.subscribeAll(this::dispatch));
        subscriptions.add(events.subscribe(dev.forge.auth.AuthEvents.SessionEnded.class, event ->
                java.util.List.copyOf(clients).stream().filter(client -> client.session.equals(event.sessionId())).forEach(Client::dispose)));
    }

    @Override
    public void dispose() {
        subscriptions.dispose();
        java.util.List.copyOf(clients).forEach(Client::dispose);
        clients.clear();
        clientsBySession.clear();
    }

    public Client open(SessionId session) {
        synchronized (clientsBySession) {
            if (clients.size() >= maxClients) {
                throw ForgeException.unavailable("Too many event-stream clients");
            }
            AtomicInteger count = clientsBySession.computeIfAbsent(session, ignored -> new AtomicInteger());
            if (count.get() >= maxClientsPerSession) {
                if (count.get() == 0) {
                    clientsBySession.remove(session, count);
                }
                throw ForgeException.unavailable("Too many event streams for this session");
            }
            count.incrementAndGet();
            Client client = new Client(session);
            clients.add(client);
            log.with("sessionId", session).debug("Event stream opened");
            return client;
        }
    }

    public int connectedClients() {
        return clients.size();
    }

    private void dispatch(Event event) {
        if (clients.isEmpty()) {
            return;
        }
        String encoded = encode(event);
        for (Client client : clients) {
            if (visibility.test(client.session, event)) {
                client.offer(encoded);
            }
        }
    }

    private String encode(Event event) {
        Frame frame = new Frame(event.type(),
                event.workspaceId() == null ? null : event.workspaceId().value(), event);
        return "event: " + event.type() + "\ndata: " + json.write(frame) + "\n\n";
    }

}
