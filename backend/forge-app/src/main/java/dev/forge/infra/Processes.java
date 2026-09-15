package dev.forge.infra;

import dev.forge.core.ForgeException;
import dev.forge.core.Log;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs a short-lived external command and collects its output.
 *
 * <p>Used by infrastructure that integrates with a command-line tool — today, Git. Kept separate
 * from the terminal feature because the two want opposite things: a terminal streams forever and
 * is interactive; this waits for a bounded result and must time out.
 *
 * <p>Arguments are always passed as a list, never assembled into a shell string, so a filename
 * containing a space, a quote or a semicolon cannot become another command.
 */
final class Processes {

    private static final Log log = Log.of(Processes.class);
    private static final int MAX_OUTPUT_BYTES = 4 * 1024 * 1024;

    record Result(int exitCode, String output) {
        boolean ok() {
            return exitCode == 0;
        }

        /** Fails with the tool's own message, which is what a user needs to see. */
        String orThrow(String what) {
            if (!ok()) {
                String message = output.isBlank() ? what + " failed" : output.strip();
                throw ForgeException.conflict(trim(message));
            }
            return output;
        }

        private static String trim(String message) {
            String single = message.lines().findFirst().orElse(message);
            return single.length() > 300 ? single.substring(0, 300) : single;
        }
    }

    private Processes() {
    }

    static Result run(Path directory, java.time.Duration timeout, List<String> command) {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(directory.toFile());
        builder.redirectErrorStream(true);
        // As with terminals, the child gets a minimal environment rather than the server's.
        var environment = builder.environment();
        environment.keySet().removeIf(key -> key.startsWith("IDE_"));
        environment.put("GIT_TERMINAL_PROMPT", "0");
        environment.put("GIT_ASKPASS", "");

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw ForgeException.unavailable("Could not run " + command.get(0) + ": " + e.getMessage());
        }
        try (var input = process.getInputStream()) {
            byte[] output = input.readNBytes(MAX_OUTPUT_BYTES);
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw ForgeException.unavailable(command.get(0) + " timed out");
            }
            return new Result(process.exitValue(), new String(output, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw ForgeException.internal("Could not read process output", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw ForgeException.cancelled("Interrupted while running " + command.get(0));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            log.with("command", command.get(0)).debug("External command finished");
        }
    }
}
