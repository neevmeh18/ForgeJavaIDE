package dev.forge.infra;

import dev.forge.terminal.Spec;
import dev.forge.core.ForgeException;
import dev.forge.core.TerminalId;
import dev.forge.core.WorkspaceId;
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

public final class ProcessTerminalProvider implements TerminalProvider {

    private static final Log log = Log.of(ProcessTerminalProvider.class);
    private static final int READ_BUFFER = 8192;
    private static final String TERMINAL_HOST = "forge-terminal";
    private static final String TERMINAL_PORT = "2222";
    private static final String TERMINAL_PASSWORD_FILE = "/run/forge-terminal-client-secret/password";

    public enum ExecutionTarget {
        LOCAL, REMOTE
    }

    private final LocalWorkspaceProvider workspaces;
    private final boolean enabled;
    private final ExecutionTarget target;

    ProcessTerminalProvider(LocalWorkspaceProvider workspaces, boolean enabled) {
        this(workspaces, enabled, ExecutionTarget.LOCAL);
    }

    public ProcessTerminalProvider(LocalWorkspaceProvider workspaces, boolean enabled, ExecutionTarget target) {
        this.workspaces = java.util.Objects.requireNonNull(workspaces);
        this.enabled = enabled;
        this.target = java.util.Objects.requireNonNull(target);
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
        ProcessBuilder builder = target == ExecutionTarget.REMOTE
                ? remoteBuilder(directory, spec)
                : localBuilder(directory, spec);

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw ForgeException.unavailable("Could not start terminal process");
        }
        log.with("terminalId", spec.id()).with("workspaceId", spec.workspace()).debug("Process started");

        Thread output = Thread.ofVirtual().name("forge-term-out").start(() -> pump(process.getInputStream(), onOutput));
        process.onExit().thenAccept(exited -> {
            try {
                output.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            onExit.accept(exited.exitValue());
        });
        return new ProcessSession(spec.id(), spec.workspace(), process);
    }


    private static ProcessBuilder localBuilder(Path directory, Spec spec) {
        List<String> command = new ArrayList<>();
        command.add(spec.shell());
        command.addAll(spec.arguments());
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(directory.toFile());
        builder.redirectErrorStream(true);
        Map<String, String> environment = builder.environment();
        environment.clear();
        environment.put("PATH", System.getenv().getOrDefault("PATH", "/usr/local/bin:/usr/bin:/bin"));
        environment.put("HOME", "/tmp");
        environment.put("TERM", "dumb");
        environment.put("LANG", "C.UTF-8");
        environment.putAll(spec.env());
        return builder;
    }

    private static ProcessBuilder remoteBuilder(Path directory, Spec spec) {
        List<String> command = new ArrayList<>();
        command.add("sshpass");
        command.add("-f");
        command.add(TERMINAL_PASSWORD_FILE);
        command.add("ssh");
        command.add("-tt");
        command.add("-e");
        command.add("none");
        command.add("-p");
        command.add(TERMINAL_PORT);
        command.add("-o");
        command.add("StrictHostKeyChecking=no");
        command.add("-o");
        command.add("UserKnownHostsFile=/dev/null");
        command.add("-o");
        command.add("LogLevel=ERROR");
        command.add("-o");
        command.add("ClearAllForwardings=yes");
        command.add("-o");
        command.add("PermitLocalCommand=no");
        command.add("-o");
        command.add("RequestTTY=force");
        command.add("forge@" + TERMINAL_HOST);
        command.add(remoteCommand(directory, spec));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Map<String, String> environment = builder.environment();
        environment.clear();
        environment.put("PATH", System.getenv().getOrDefault("PATH", "/usr/local/bin:/usr/bin:/bin"));
        environment.put("HOME", "/tmp");
        environment.put("TERM", "dumb");
        environment.put("LANG", "C.UTF-8");
        return builder;
    }

    private Path resolveWorkingDirectory(Spec spec) {
        Path base = workspaces.directory(spec.workspace())
                .orElseThrow(() -> ForgeException.unavailable("Workspace is not open locally"));
        Path directory = base.resolve(spec.cwd()).normalize();
        SafePaths.noLinks(directory);
        try {
            Path real = directory.toRealPath();
            if (!real.startsWith(base) || !java.nio.file.Files.isDirectory(real)) {
                throw ForgeException.invalidArgument("Working directory is not a directory");
            }
            return real;
        } catch (IOException e) {
            throw ForgeException.invalidArgument("Working directory does not exist");
        }
    }

    private static String remoteCommand(Path directory, Spec spec) {
        StringBuilder command = new StringBuilder("cd -- ").append(shellQuote(directory.toString())).append(" && exec env");
        command.append(" TERM=").append(shellQuote("dumb"));
        command.append(" LANG=").append(shellQuote("C.UTF-8"));
        for (Map.Entry<String, String> entry : spec.env().entrySet()) {
            command.append(' ').append(shellQuote(entry.getKey() + "=" + entry.getValue()));
        }
        command.append(' ').append(shellQuote(spec.shell()));
        for (String argument : spec.arguments()) {
            command.append(' ').append(shellQuote(argument));
        }
        return command.toString();
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static void pump(InputStream input, Consumer<String> onOutput) {
        char[] buffer = new char[READ_BUFFER];
        try (var reader = new java.io.InputStreamReader(input, StandardCharsets.UTF_8)) {
            int count;
            while ((count = reader.read(buffer)) >= 0) {
                if (count > 0) {
                    onOutput.accept(new String(buffer, 0, count));
                }
            }
        } catch (IOException e) {
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
        }

        @Override
        public void kill() {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }

        @Override
        public boolean isAlive() {
            return process.isAlive();
        }
    }
}
