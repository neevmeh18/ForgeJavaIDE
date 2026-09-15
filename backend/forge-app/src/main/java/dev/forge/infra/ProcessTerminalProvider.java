package dev.forge.infra;

import dev.forge.core.ForgeException;
import dev.forge.core.Ids.TerminalId;
import dev.forge.core.Ids.WorkspaceId;
import dev.forge.core.Log;
import dev.forge.terminal.TerminalProvider;
import dev.forge.terminal.TerminalSession;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Terminals as child processes of the backend.
 *
 * <p>No pseudo-terminal: that would mean a native dependency, and the framework's dependency
 * policy says a library must earn its place. The practical consequence is honest and documented
 * — line-oriented shells, task output and build logs work; full-screen curses programs and job
 * control do not. A product that needs them registers a PTY-backed {@link TerminalProvider}
 * instead, and nothing above the interface changes.
 *
 * <p><b>Environment hygiene.</b> The child does not inherit the server's environment. It is
 * given a small, explicit set of variables, so the IDE's own configuration — including
 * {@code IDE_AUTH_PASSWORD} — cannot be read by anything the user runs in a terminal.
 */
public final class ProcessTerminalProvider implements TerminalProvider {

    private static final Log log = Log.of(ProcessTerminalProvider.class);
    private static final int READ_BUFFER = 8192;

    private final Path workspaceRoot;
    private final boolean enabled;

    public ProcessTerminalProvider(Path workspaceRoot, boolean enabled) {
        this.workspaceRoot = workspaceRoot;
        this.enabled = enabled;
    }

    @Override
    public boolean isAvailable() {
        return enabled;
    }

    @Override
    public TerminalSession create(Spec spec, Consumer<String> onOutput, IntConsumer onExit) {
        if (!enabled) {
            throw ForgeException.unavailable("Terminals are disabled in this deployment");
        }
        Path directory = resolveWorkingDirectory(spec);
        List<String> command = new ArrayList<>();
        command.add(spec.shell());
        command.addAll(spec.arguments());

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(directory.toFile());
        builder.redirectErrorStream(true);
        Map<String, String> environment = builder.environment();
        environment.clear();
        environment.put("PATH", System.getenv().getOrDefault("PATH", "/usr/local/bin:/usr/bin:/bin"));
        environment.put("HOME", System.getenv().getOrDefault("HOME", directory.toString()));
        environment.put("TERM", "dumb");
        environment.put("LANG", "C.UTF-8");
        environment.put("PWD", directory.toString());
        environment.putAll(spec.env());

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw ForgeException.unavailable("Could not start '" + spec.shell() + "': " + e.getMessage());
        }
        log.with("terminalId", spec.id()).with("workspaceId", spec.workspace()).debug("Process started");

        Thread.ofVirtual().name("forge-term-out").start(() -> pump(process.getInputStream(), onOutput));
        process.onExit().thenAccept(exited -> onExit.accept(exited.exitValue()));
        return new ProcessSession(spec.id(), spec.workspace(), process);
    }

    /** The service already normalised the path; this maps it onto the real directory. */
    private Path resolveWorkingDirectory(Spec spec) {
        Path base = workspaceRoot.toAbsolutePath().normalize();
        Path directory = base.resolve(spec.cwd()).normalize();
        if (!directory.startsWith(base) || !directory.toFile().isDirectory()) {
            return base;
        }
        return directory;
    }

    private static void pump(InputStream input, Consumer<String> onOutput) {
        byte[] buffer = new byte[READ_BUFFER];
        try (input) {
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) {
                    onOutput.accept(new String(buffer, 0, count, StandardCharsets.UTF_8));
                }
            }
        } catch (IOException e) {
            // The stream closes when the process ends; the exit callback reports that.
            log.debug("Terminal output stream closed");
        }
    }

    private record ProcessSession(TerminalId id, WorkspaceId workspaceId, Process process)
            implements TerminalSession {

        @Override
        public void write(String data) {
            try {
                OutputStream input = process.getOutputStream();
                input.write(data.getBytes(StandardCharsets.UTF_8));
                input.flush();
            } catch (IOException e) {
                throw ForgeException.unavailable("Terminal is no longer accepting input");
            }
        }

        @Override
        public void resize(int columns, int rows) {
            // Without a pseudo-terminal there is no window size to set. Accepted and ignored so
            // that clients need not special-case this provider.
        }

        @Override
        public void kill() {
            process.descendants().forEach(ProcessHandle::destroy);
            process.destroy();
        }

        @Override
        public boolean isAlive() {
            return process.isAlive();
        }
    }
}
