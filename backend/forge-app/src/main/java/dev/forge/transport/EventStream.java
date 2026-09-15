package dev.forge.transport;

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
import java.util.function.BiPredicate;

/**
 * Fans events out to connected clients, and decides who is allowed to see what.
 *
 * <p>Delivery is filtered, not broadcast. An event addressed to a session goes only there; an
 * event belonging to a workspace goes only to sessions attached to that workspace; the rest
 * reach every authenticated client. Without this, knowing the event stream URL would leak other
 * people's file changes and terminal output.
 *
 * <p>Each client has a bounded queue. A client that cannot keep up — a closed laptop lid, a
 * stalled connection — loses its oldest events rather than growing the server's heap.
 */
public final class EventStream implements Lifecycle.Component {

    private static final Log log = Log.of(EventStream.class);
    private static final int QUEUE_CAPACITY = 4096;

    /** The wire shape of an event: a name, its scope, and the event's own fields as payload. */
    private record Frame(String type, String workspaceId, Object payload) {
    }

    /** One connected client. The HTTP handler owns it and pumps it until the socket closes. */
    public final class Client implements Lifecycle.Disposable {

        private final SessionId session;
        private final BlockingQueue<String> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);

        private Client(SessionId session) {
            this.session = session;
        }

        /** Next encoded event, or {@code null} when the wait elapsed and a heartbeat is due. */
        public String poll(Duration timeout) throws InterruptedException {
            return queue.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        private void offer(String frame) {
            while (!queue.offer(frame)) {
                queue.poll();
            }
        }

        @Override
        public void dispose() {
            clients.remove(this);
        }
    }

    private final Set<Client> clients = ConcurrentHashMap.newKeySet();
    private final EventBus events;
    private final Json json;
    private final BiPredicate<SessionId, Event> visibility;
    private final Lifecycle.Store subscriptions = new Lifecycle.Store();

    public EventStream(EventBus events, Json json, BiPredicate<SessionId, Event> visibility) {
        this.events = events;
        this.json = json;
        this.visibility = visibility;
    }

    @Override
    public void start() {
        subscriptions.add(events.subscribeAll(this::dispatch));
    }

    @Override
    public void dispose() {
        subscriptions.dispose();
        clients.clear();
    }

    public Client open(SessionId session) {
        Client client = new Client(session);
        clients.add(client);
        log.with("sessionId", session).debug("Event stream opened");
        return client;
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

    /** Server-sent-events framing: a named event whose data is one line of JSON. */
    private String encode(Event event) {
        Frame frame = new Frame(event.type(),
                event.workspaceId() == null ? null : event.workspaceId().value(), event);
        return "event: " + event.type() + "\ndata: " + json.write(frame) + "\n\n";
    }
}
