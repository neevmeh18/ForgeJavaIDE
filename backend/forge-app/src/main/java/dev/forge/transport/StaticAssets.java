package dev.forge.transport;

import com.sun.net.httpserver.HttpExchange;
import dev.forge.core.Log;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Serves the built frontend from the backend.
 *
 * <p>One origin for the UI and the API means no CORS, no cookie sharing across ports and one
 * container to run — and it costs nothing architecturally, because the frontend is a static
 * bundle that talks to the same gateway any other client would.
 *
 * <p>Unknown paths fall back to {@code index.html} so the workbench can own its routing, but
 * only after the request has been resolved inside the web root: a path that escapes is a 404,
 * never a read.
 */
public final class StaticAssets {

    private static final Log log = Log.of(StaticAssets.class);

    private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"),
            Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("mjs", "text/javascript; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("json", "application/json"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("woff", "font/woff"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("map", "application/json"),
            Map.entry("ttf", "font/ttf"));

    private final Path root;

    public StaticAssets(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public boolean isAvailable() {
        return Files.isDirectory(root) && Files.isReadable(root.resolve("index.html"));
    }

    public void serve(HttpExchange exchange) throws IOException {
        String requested = exchange.getRequestURI().getPath();
        Path file = resolve(requested);
        if (file == null) {
            file = root.resolve("index.html");
            if (!Files.isReadable(file)) {
                respond(exchange, 404, "text/plain; charset=utf-8", "Not found".getBytes(), false);
                return;
            }
        }
        byte[] body = Files.readAllBytes(file);
        String name = file.getFileName().toString();
        boolean immutable = !name.equals("index.html") && name.matches(".*-[A-Za-z0-9_]{8,}\\..*");
        String type = contentType(name);
        if (isCompressible(type) && body.length > 1024 && acceptsGzip(exchange)) {
            exchange.getResponseHeaders().set("Content-Encoding", "gzip");
            body = gzip(body);
        }
        respond(exchange, 200, type, body, immutable);
    }

    private static boolean acceptsGzip(HttpExchange exchange) {
        String accepted = exchange.getRequestHeaders().getFirst("Accept-Encoding");
        return accepted != null && accepted.toLowerCase(java.util.Locale.ROOT).contains("gzip");
    }

    private static boolean isCompressible(String contentType) {
        return contentType.startsWith("text/") || contentType.contains("json")
                || contentType.contains("javascript") || contentType.contains("svg");
    }

    private static byte[] gzip(byte[] body) throws IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream(body.length / 3);
        try (java.util.zip.GZIPOutputStream out = new java.util.zip.GZIPOutputStream(buffer)) {
            out.write(body);
        }
        return buffer.toByteArray();
    }

    /** Returns the file only if it exists and stays under the web root. */
    private Path resolve(String requested) {
        if (requested == null || requested.isEmpty() || requested.equals("/")) {
            return root.resolve("index.html");
        }
        Path candidate = root.resolve(requested.substring(1)).normalize();
        if (!candidate.startsWith(root)) {
            log.with("path", requested).warn("Rejected static path outside the web root");
            return null;
        }
        return Files.isRegularFile(candidate) ? candidate : null;
    }

    private static String contentType(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "application/octet-stream"
                : CONTENT_TYPES.getOrDefault(name.substring(dot + 1), "application/octet-stream");
    }

    private static void respond(HttpExchange exchange, int status, String contentType, byte[] body,
                                boolean immutable) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control",
                immutable ? "public, max-age=31536000, immutable" : "no-cache");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
