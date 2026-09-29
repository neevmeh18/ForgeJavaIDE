package dev.forge.app;

import dev.forge.core.ForgeException;
import dev.forge.core.Ids.UserId;
import dev.forge.core.Ids.WorkspaceId;
import dev.forge.infra.LocalWorkspaceProvider;
import dev.forge.state.StateStore;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

public final class BackupService {
    public record Configuration(String destination) { }
    public record Result(String destination, String status) { }

    private static final String STATE_KEY = "backup.destinations";

    private final LocalWorkspaceProvider workspaces;
    private final URI workerUri;
    private final String workerToken;
    private final HttpClient client;
    private final StateStore state;

    public BackupService(LocalWorkspaceProvider workspaces, StateStore state, String workerUrl, String workerToken) {
        this.workspaces = workspaces;
        this.state = state;
        this.workerUri = URI.create(workerUrl.endsWith("/") ? workerUrl + "backup" : workerUrl + "/backup");
        this.workerToken = workerToken;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    public Configuration configuration(UserId userId, WorkspaceId workspaceId) {
        Object value = destinations(userId).get(workspaceId.value());
        return value instanceof String destination && !destination.isBlank()
                ? new Configuration(destination)
                : new Configuration("workspace-backup.zip");
    }

    public Configuration configure(UserId userId, WorkspaceId workspaceId, String destination) {
        String value = destination == null ? "" : destination.strip();
        if (value.isEmpty() || value.length() > 240 || value.indexOf('\0') >= 0) {
            throw ForgeException.invalidArgument("Invalid backup destination");
        }
        Map<String, Object> destinations = new LinkedHashMap<>(destinations(userId));
        destinations.put(workspaceId.value(), value);
        state.put(StateStore.Scope.USER, userId.value(), STATE_KEY, destinations);
        return new Configuration(value);
    }

    public Result run(UserId userId, WorkspaceId workspaceId) {
        Path source = workspaces.directory(workspaceId)
                .orElseThrow(() -> ForgeException.unavailable("Workspace is not open"));
        Configuration configuration = configuration(userId, workspaceId);
        String body = "{\"user\":\"" + json(userId.value()) + "\",\"workspace\":\""
                + json(workspaceId.value()) + "\",\"source\":\"" + json(source.toString())
                + "\",\"destination\":\"" + json(configuration.destination()) + "\"}";
        HttpRequest request = HttpRequest.newBuilder(workerUri)
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + workerToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw ForgeException.unavailable("Backup worker rejected the request");
            }
            return new Result(configuration.destination(), "Completed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ForgeException.unavailable("Backup interrupted");
        } catch (IOException e) {
            throw ForgeException.unavailable("Backup worker unavailable");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> destinations(UserId userId) {
        Object value = state.get(StateStore.Scope.USER, userId.value(), STATE_KEY).orElse(null);
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
