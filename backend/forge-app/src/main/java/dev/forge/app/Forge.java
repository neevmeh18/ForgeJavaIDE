package dev.forge.app;

import dev.forge.core.Log;
import dev.forge.infra.Config;
import java.util.logging.ConsoleHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Entry point.
 *
 * <p>Deliberately thin: configure logging, read the environment, build the application, install
 * a shutdown hook, wait. Everything interesting is in {@link ForgeApplication}, and the same
 * assembly runs unchanged from a terminal, from an IDE, and inside the container.
 */
public final class Forge {

    public static final String PRODUCT = "Forge";
    public static final String VERSION = "0.1.0";

    private Forge() {
    }

    public static void main(String[] args) {
        Config config = Config.fromEnvironment();
        configureLogging(config.logLevel());

        ForgeApplication application = new ForgeApplication(config);
        Runtime.getRuntime().addShutdownHook(new Thread(application::dispose, "forge-shutdown"));
        try {
            application.start();
        } catch (RuntimeException e) {
            Log.of(Forge.class).error("Startup failed", e);
            application.dispose();
            Runtime.getRuntime().halt(1);
        }
        // The HTTP server owns its own threads; park the main thread until the JVM is told to go.
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * One-line structured output on stdout — the format container log collectors expect. The
     * framework logs through {@code System.Logger}, so this configures the JDK backend rather
     * than pulling in a logging framework.
     */
    private static void configureLogging(String levelName) {
        Level level = parse(levelName);
        Logger root = Logger.getLogger("");
        for (var handler : root.getHandlers()) {
            root.removeHandler(handler);
        }
        ConsoleHandler handler = new ConsoleHandler() {
            {
                setOutputStream(System.out);
            }
        };
        handler.setLevel(level);
        handler.setFormatter(new Formatter() {
            @Override
            public String format(LogRecord record) {
                StringBuilder line = new StringBuilder()
                        .append(java.time.Instant.ofEpochMilli(record.getMillis()))
                        .append(' ').append(record.getLevel().getName())
                        .append(' ').append(shortName(record.getLoggerName()))
                        .append(" - ").append(formatMessage(record))
                        .append(System.lineSeparator());
                if (record.getThrown() != null) {
                    java.io.StringWriter writer = new java.io.StringWriter();
                    record.getThrown().printStackTrace(new java.io.PrintWriter(writer));
                    line.append(writer);
                }
                return line.toString();
            }
        });
        root.addHandler(handler);
        root.setLevel(level);
    }

    private static String shortName(String logger) {
        if (logger == null) {
            return "forge";
        }
        int dot = logger.lastIndexOf('.');
        return dot < 0 ? logger : logger.substring(dot + 1);
    }

    private static Level parse(String name) {
        try {
            return switch (name.toUpperCase(java.util.Locale.ROOT)) {
                case "DEBUG", "FINE" -> Level.FINE;
                case "WARN", "WARNING" -> Level.WARNING;
                case "ERROR", "SEVERE" -> Level.SEVERE;
                default -> Level.INFO;
            };
        } catch (RuntimeException e) {
            return Level.INFO;
        }
    }
}
