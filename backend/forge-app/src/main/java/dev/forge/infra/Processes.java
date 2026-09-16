package dev.forge.infra;

import dev.forge.core.ForgeException;
import dev.forge.core.Log;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Runs one bounded non-interactive child process, currently used by Git. */
final class Processes {

    private static final Log log = Log.of(Processes.class);
    private static final java.util.concurrent.Semaphore SLOTS = new java.util.concurrent.Semaphore(8);
    private static final int MAX_OUTPUT_BYTES = 4 * 1024 * 1024;

    record Result(int exitCode, String output) {
        boolean ok() {
            return exitCode == 0;
        }

        String orThrow(String what) {
            if (!ok()) {
                log.debug("External operation failed: " + what);
                throw ForgeException.conflict(what + " failed; check repository state and server logs");
            }
            return output;
        }


    }

    private Processes() { }

    static Result run(Path directory, java.time.Duration timeout, List<String> command) {
        if (!SLOTS.tryAcquire()) throw ForgeException.unavailable("Too many helper processes");
        try { return runBounded(directory, timeout, command); } finally { SLOTS.release(); }
    }

    private static Result runBounded(Path directory, java.time.Duration timeout, List<String> command) {
        SafePaths.noLinks(directory);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(directory.toFile());
        builder.redirectErrorStream(true);
        Map<String, String> environment = builder.environment();
        environment.clear();
        environment.put("PATH", System.getenv().getOrDefault("PATH", "/usr/local/bin:/usr/bin:/bin"));
        environment.put("HOME", directory.toString());
        environment.put("GIT_CONFIG_NOSYSTEM", "1");
        environment.put("LANG", "C.UTF-8");
        environment.put("GIT_TERMINAL_PROMPT", "0");
        environment.put("GIT_ASKPASS", "");

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw ForgeException.unavailable("Could not run " + command.get(0));
        }

        java.util.concurrent.atomic.AtomicBoolean truncated = new java.util.concurrent.atomic.AtomicBoolean();
        ByteArrayOutputStream captured = new ByteArrayOutputStream(Math.min(MAX_OUTPUT_BYTES, 64 * 1024));
        Thread reader = Thread.ofVirtual().name("forge-process-output").start(() ->
                drain(process.getInputStream(), captured, truncated));
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                destroyTree(process);
                throw ForgeException.unavailable(command.get(0) + " timed out");
            }
            try {
                reader.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                destroyTree(process);
                throw ForgeException.cancelled("Interrupted while running " + command.get(0));
            }
            if (reader.isAlive()) {
                try { process.getInputStream().close(); } catch (IOException ignored) { }
                throw ForgeException.unavailable("Process output did not close in time");
            }
            if (truncated.get()) throw ForgeException.unavailable("Process output exceeded the supported limit; narrow the operation");
            return new Result(process.exitValue(), captured.toString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            destroyTree(process);
            throw ForgeException.cancelled("Interrupted while running " + command.get(0));
        } finally {
            if (process.isAlive()) {
                destroyTree(process);
            }
            log.with("command", command.get(0)).debug("External command finished");
        }
    }

    private static void drain(InputStream input, ByteArrayOutputStream captured, java.util.concurrent.atomic.AtomicBoolean truncated) {
        byte[] buffer = new byte[8192];
        int retained = 0;
        try (input) {
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (retained + count > MAX_OUTPUT_BYTES) truncated.set(true);
                if (count <= 0 || retained >= MAX_OUTPUT_BYTES) {
                    continue;
                }
                int copy = Math.min(count, MAX_OUTPUT_BYTES - retained);
                synchronized (captured) {
                    captured.write(buffer, 0, copy);
                }
                retained += copy;
            }
        } catch (IOException ignored) {
            // Process termination closes the stream.
        }
    }

    private static void destroyTree(Process process) {
        process.descendants().forEach(handle -> {
            try {
                handle.destroyForcibly();
            } catch (RuntimeException ignored) {
                // Best effort.
            }
        });
        process.destroyForcibly();
    }
}
