package dev.forge.infra;

import dev.forge.core.Log;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Deployment configuration, read from the environment.
 *
 * <p>Every value has a local default, so {@code docker compose up --build} and a plain
 * {@code mvn exec} both work with nothing set. Nothing here selects a different architecture:
 * these are paths, ports and limits — the application is assembled the same way whether it is
 * running on a laptop, in a container or in a hosted environment.
 *
 * <p>Names are few and plain on purpose. Internal knobs belong in settings, where they can be
 * typed, documented and changed without a restart.
 */
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
        long maxFileBytes) {

    private static final Log log = Log.of(Config.class);

    /** Used when no password is configured. Startup warns loudly; never use it off a laptop. */
    public static final String DEVELOPMENT_PASSWORD = "forge";

    public static Config fromEnvironment() {
        String password = env("IDE_AUTH_PASSWORD", "");
        if (password.isBlank()) {
            password = DEVELOPMENT_PASSWORD;
        }
        return new Config(
                env("IDE_HOST", "0.0.0.0"),
                intEnv("IDE_PORT", 3000),
                Path.of(env("IDE_WORKSPACE_ROOT", "/workspace")),
                Path.of(env("IDE_DATA_DIR", "/data")),
                Path.of(env("IDE_WEB_ROOT", "/app/web")),
                Path.of(env("IDE_EXTENSIONS_DIR", "/app/extensions")),
                env("IDE_LOG_LEVEL", "INFO"),
                env("IDE_AUTH_USER", "developer"),
                password,
                env("IDE_SHELL", "/bin/sh"),
                !"false".equalsIgnoreCase(env("IDE_TERMINAL_ENABLED", "true")),
                Duration.ofMinutes(intEnv("IDE_SESSION_IDLE_MINUTES", 480)),
                Duration.ofHours(intEnv("IDE_SESSION_MAX_HOURS", 72)),
                intEnv("IDE_MAX_FILE_MB", 8) * 1024L * 1024L);
    }

    /** Called once at startup so an insecure default can never pass unnoticed. */
    public void warnAboutDevelopmentDefaults() {
        if (DEVELOPMENT_PASSWORD.equals(authPassword)) {
            log.warn("IDE_AUTH_PASSWORD is not set; using the built-in development password. "
                    + "Set IDE_AUTH_PASSWORD before exposing this instance to anything but localhost.");
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int intEnv(String name, int fallback) {
        try {
            return Integer.parseInt(env(name, String.valueOf(fallback)));
        } catch (NumberFormatException e) {
            log.warn("Ignoring invalid " + name + "; using " + fallback);
            return fallback;
        }
    }
}
