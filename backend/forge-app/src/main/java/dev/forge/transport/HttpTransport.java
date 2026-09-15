package dev.forge.transport;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.forge.core.Args;
import dev.forge.core.Cancellation;
import dev.forge.core.ForgeException;
import dev.forge.core.Lifecycle;
import dev.forge.core.Log;
import dev.forge.core.RequestContext;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * HTTP and server-sent events, the only transport implementation the framework ships.
 *
 * <p>Four endpoints, not one per feature:
 * <pre>
 *   POST /api/command   run a command
 *   POST /api/query     run a query
 *   GET  /api/events    subscribe to the event stream
 *   GET  /api/health    readiness
 * </pre>
 * Everything else is the frontend bundle. Adding WebSocket or JSON-RPC later means adding a
 * sibling of this class, because all it does is translate — the {@link Gateway} it calls has no
 * idea HTTP exists.
 *
 * <p>Credentials travel only in the {@code Authorization} header, including on the event stream
 * (read with {@code fetch}, not {@code EventSource}). With no cookie there is no ambient
 * authority and therefore no CSRF surface.
 */
public final class HttpTransport implements Lifecycle.Component {

    private static final Log log = Log.of(HttpTransport.class);
    private static final long MAX_BODY_BYTES = 16L * 1024 * 1024;
    private static final Duration HEARTBEAT = Duration.ofSeconds(20);

    private static final String CONTENT_SECURITY_POLICY = String.join("; ",
            "default-src 'self'",
            // Monaco compiles its tokenizers at runtime and loads its workers as blobs.
            "script-src 'self' 'unsafe-eval' blob:",
            "worker-src 'self' blob:",
            "style-src 'self' 'unsafe-inline'",
            "img-src 'self' data:",
            "font-src 'self' data:",
            "connect-src 'self'",
            "frame-ancestors 'none'",
            "base-uri 'self'",
            "form-action 'self'");

    private final Gateway gateway;
    private final Json json;
    private final EventStream stream;
    private final StaticAssets assets;
    private final Supplier<Boolean> readiness;
    private final String host;
    private final int port;
    private HttpServer server;

    public HttpTransport(Gateway gateway, Json json, EventStream stream, StaticAssets assets,
                         Supplier<Boolean> readiness, String host, int port) {
        this.gateway = gateway;
        this.json = json;
        this.stream = stream;
        this.assets = assets;
        this.readiness = readiness;
        this.host = host;
        this.port = port;
    }

    @Override
    public void start() {
        try {
            server = HttpServer.create(new InetSocketAddress(host, port), 64);
        } catch (IOException e) {
            throw ForgeException.internal("Could not bind " + host + ":" + port, e);
        }
        // Virtual threads: a blocked request (a long command, an idle event stream) costs a
        // stack rather than a platform thread, so thousands of open streams stay cheap.
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/api/command", exchange -> handle(exchange, this::command));
        server.createContext("/api/query", exchange -> handle(exchange, this::query));
        server.createContext("/api/events", exchange -> handle(exchange, this::events));
        server.createContext("/api/health", exchange -> handle(exchange, this::health));
        server.createContext("/", exchange -> handle(exchange, assets::serve));
        server.start();
        if (!assets.isAvailable()) {
            log.warn("No frontend bundle found; the API is served but the workbench will not load. "
                    + "Check IDE_WEB_ROOT.");
        }
        log.with("port", port).info("HTTP transport listening");
    }

    @Override
    public void dispose() {
        if (server != null) {
            server.stop(1);
        }
    }

    @FunctionalInterface
    private interface Route {
        void handle(HttpExchange exchange) throws IOException;
    }

    private void handle(HttpExchange exchange, Route route) throws IOException {
        securityHeaders(exchange);
        try {
            route.handle(exchange);
        } catch (ForgeException e) {
            writeJson(exchange, statusFor(e.code()), new Gateway.Result(false, null,
                    new Gateway.ErrorView(e.code().name(), e.getMessage(), e.details()), null, false));
        } catch (RuntimeException e) {
            log.error("Unhandled transport failure", e);
            writeJson(exchange, 500, new Gateway.Result(false, null,
                    new Gateway.ErrorView("INTERNAL_FAILURE", "An internal error occurred", Map.of()),
                    null, false));
        } finally {
            exchange.close();
        }
    }

    private void command(HttpExchange exchange) throws IOException {
        requirePost(exchange);
        Map<String, Object> body = readBody(exchange);
        Cancellation cancellation = new Cancellation();
        RequestContext ctx = context(exchange, cancellation);
        String id = requireString(body, "id");
        Args args = Args.of(nested(body, "args"));
        boolean async = Boolean.TRUE.equals(body.get("async"));

        Gateway.Result result = gateway.command(id, args, async, ctx);
        writeJson(exchange, result.ok() ? 200 : statusFor(codeOf(result)), result);
    }

    private void query(HttpExchange exchange) throws IOException {
        requirePost(exchange);
        Map<String, Object> body = readBody(exchange);
        Cancellation cancellation = new Cancellation();
        RequestContext ctx = context(exchange, cancellation);

        Gateway.Result result = gateway.query(requireString(body, "id"), Args.of(nested(body, "args")), ctx);
        writeJson(exchange, result.ok() ? 200 : statusFor(codeOf(result)), result);
    }

    /**
     * The event stream. Held open for the life of the session; a heartbeat comment keeps
     * intermediaries from closing an idle connection.
     */
    private void events(HttpExchange exchange) throws IOException {
        RequestContext ctx = context(exchange, new Cancellation());
        if (!ctx.isAuthenticated()) {
            throw ForgeException.unauthorized("Authentication required");
        }
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-transform");
        exchange.getResponseHeaders().set("X-Accel-Buffering", "no");
        exchange.sendResponseHeaders(200, 0);

        EventStream.Client client = stream.open(ctx.sessionId());
        try (Writer writer = new OutputStreamWriter(exchange.getResponseBody(), StandardCharsets.UTF_8)) {
            writer.write(": connected\n\n");
            writer.flush();
            while (!Thread.currentThread().isInterrupted()) {
                String frame = client.poll(HEARTBEAT);
                writer.write(frame == null ? ": ping\n\n" : frame);
                writer.flush();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            log.with("sessionId", ctx.sessionId()).debug("Event stream closed by client");
        } finally {
            client.dispose();
        }
    }

    /**
     * Readiness, not liveness: reports healthy only when the application can actually serve
     * requests, so an orchestrator waits for something useful rather than for a live process.
     */
    private void health(HttpExchange exchange) throws IOException {
        boolean ready = Boolean.TRUE.equals(readiness.get());
        writeJson(exchange, ready ? 200 : 503, Map.of(
                "status", ready ? "ready" : "starting",
                "eventClients", stream.connectedClients()));
    }

    private RequestContext context(HttpExchange exchange, Cancellation cancellation) {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        String token = authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)
                ? authorization.substring(7).trim()
                : null;
        return gateway.contextFor(token, exchange.getRequestHeaders().getFirst("X-Forge-Workspace"),
                cancellation);
    }

    private Map<String, Object> readBody(HttpExchange exchange) throws IOException {
        String length = exchange.getRequestHeaders().getFirst("Content-Length");
        if (length != null && Long.parseLong(length) > MAX_BODY_BYTES) {
            throw ForgeException.invalidArgument("Request body is too large");
        }
        try (InputStream input = exchange.getRequestBody()) {
            return json.readObject(new LimitedInputStream(input, MAX_BODY_BYTES));
        }
    }

    private void writeJson(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] body = json.write(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static void securityHeaders(HttpExchange exchange) {
        var headers = exchange.getResponseHeaders();
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Content-Security-Policy", CONTENT_SECURITY_POLICY);
        headers.set("Permissions-Policy", "geolocation=(), microphone=(), camera=()");
    }

    private static void requirePost(HttpExchange exchange) {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            throw ForgeException.invalidArgument("Expected POST");
        }
    }

    private static String requireString(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw ForgeException.invalidArgument("Missing '" + key + "'");
        }
        return text;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nested(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static ForgeException.Code codeOf(Gateway.Result result) {
        return result.error() == null
                ? ForgeException.Code.INTERNAL_FAILURE
                : ForgeException.Code.valueOf(result.error().code());
    }

    private static int statusFor(ForgeException.Code code) {
        return switch (code) {
            case NOT_FOUND -> 404;
            case INVALID_ARGUMENT -> 400;
            case CONFLICT -> 409;
            case UNAUTHORIZED -> 401;
            case FORBIDDEN -> 403;
            case UNAVAILABLE -> 409;
            case UNSUPPORTED -> 501;
            case CANCELLED -> 499;
            case INTERNAL_FAILURE -> 500;
        };
    }

    /** Stops an over-long body before it reaches the parser, whatever Content-Length claimed. */
    private static final class LimitedInputStream extends InputStream {

        private final InputStream delegate;
        private final long limit;
        private long read;

        LimitedInputStream(InputStream delegate, long limit) {
            this.delegate = delegate;
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0 && ++read > limit) {
                throw new IOException("Request body is too large");
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = delegate.read(buffer, offset, length);
            if (count > 0 && (read += count) > limit) {
                throw new IOException("Request body is too large");
            }
            return count;
        }
    }
}
