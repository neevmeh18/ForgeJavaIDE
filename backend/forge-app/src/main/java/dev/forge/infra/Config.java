package dev.forge.infra;

import dev.forge.core.ForgeException;
import dev.forge.core.Log;
import java.nio.file.Path;
import java.time.Duration;

/** Deployment configuration, read once from the environment. */
public record Config(
        String host,
        int port,
        Path workspaceRoot,
        Path dataDir,
        Path webRoot,
        Path extensionsDir,
        String logLevel,
        String authUsername,
        String authPassword,
        String shell,
        boolean terminalsEnabled,
        Duration sessionIdleTimeout,
        Duration sessionMaxLifetime,
        long maxFileBytes,
        long maxWorkspaceBytes,
        long maxStateBytes,
        long maxStateDocumentBytes,
        int maxOpenWorkspaces,
        int maxSessions,
        int maxCommandsGlobal,
        int maxCommandsPerSession,
        int maxEventClientsGlobal,
        int maxEventClientsPerSession,
        Duration queryTimeout,
        Duration requestBodyTimeout,
        int maxDirectoryEntries,
        int maxTraversalEntries) {

    private static final Log log = Log.of(Config.class);

    /** Former default, now explicitly rejected. */
    public static final String DEVELOPMENT_PASSWORD = "forge";

    public static Config fromEnvironment() {
        String host = env("IDE_HOST", "127.0.0.1");
        String password = env("IDE_AUTH_PASSWORD", "");
        return new Config(
                host,
                boundedIntEnv("IDE_PORT", 3000, 1, 65535),
                Path.of(env("IDE_WORKSPACE_ROOT", "/workspace")),
                Path.of(env("IDE_DATA_DIR", "/data")),
                Path.of(env("IDE_WEB_ROOT", "/app/web")),
                Path.of(env("IDE_EXTENSIONS_DIR", "/app/extensions")),
                env("IDE_LOG_LEVEL", "INFO"),
                env("IDE_AUTH_USER", "developer"),
                password,
                env("IDE_SHELL", "/bin/sh"),
                !"false".equalsIgnoreCase(env("IDE_TERMINAL_ENABLED", "true")),
                Duration.ofMinutes(boundedIntEnv("IDE_SESSION_IDLE_MINUTES", 120, 5, 1440)),
                Duration.ofHours(boundedIntEnv("IDE_SESSION_MAX_HOURS", 12, 1, 168)),
                mb("IDE_MAX_FILE_MB", 8, 1, 256),
                mb("IDE_MAX_WORKSPACE_MB", 2048, 64, 1024 * 1024),
                mb("IDE_MAX_STATE_MB", 32, 1, 1024),
                mb("IDE_MAX_STATE_DOCUMENT_MB", 1, 1, 64),
                boundedIntEnv("IDE_MAX_OPEN_WORKSPACES", 32, 1, 256),
                boundedIntEnv("IDE_MAX_SESSIONS", 64, 1, 4096),
                boundedIntEnv("IDE_MAX_COMMANDS_GLOBAL", 128, 8, 4096),
                boundedIntEnv("IDE_MAX_COMMANDS_PER_SESSION", 16, 1, 256),
                boundedIntEnv("IDE_MAX_EVENT_CLIENTS", 64, 1, 4096),
                boundedIntEnv("IDE_MAX_EVENT_CLIENTS_PER_SESSION", 2, 1, 16),
                Duration.ofSeconds(boundedIntEnv("IDE_QUERY_TIMEOUT_SECONDS", 10, 1, 120)),
                Duration.ofSeconds(boundedIntEnv("IDE_REQUEST_BODY_TIMEOUT_SECONDS", 15, 3, 120)),
                boundedIntEnv("IDE_MAX_DIRECTORY_ENTRIES", 5000, 100, 100000),
                boundedIntEnv("IDE_MAX_TRAVERSAL_ENTRIES", 100000, 1000, 1000000));
    }

    /** Refuses the dangerous combination that previously made the development credential remote. */
    public void validateSecurityDefaults() {
        if (authPassword == null || authPassword.length() < 12 || DEVELOPMENT_PASSWORD.equals(authPassword)) {
            throw ForgeException.invalidArgument("Set IDE_AUTH_PASSWORD to a unique password of at least 12 characters");
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int boundedIntEnv(String name, int fallback, int min, int max) {
        int value;
        try {
            value = Integer.parseInt(env(name, String.valueOf(fallback)));
        } catch (NumberFormatException e) {
            log.warn("Ignoring invalid " + name + "; using " + fallback);
            return fallback;
        }
        if (value < min || value > max) {
            log.warn("Ignoring out-of-range " + name + "; using " + fallback);
            return fallback;
        }
        return value;
    }

    private static long mb(String name, int fallback, int min, int max) {
        return boundedIntEnv(name, fallback, min, max) * 1024L * 1024L;
    }
}
