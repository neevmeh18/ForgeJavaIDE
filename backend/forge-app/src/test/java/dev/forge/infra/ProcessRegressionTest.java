package dev.forge.infra;

import dev.forge.auth.AuthenticationProvider;
import dev.forge.core.*;
import dev.forge.core.Ids.*;
import dev.forge.core.event.EventBus;
import dev.forge.tasks.*;
import dev.forge.terminal.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class ProcessRegressionTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void passwordAcceptsConfiguredSecretAndRejectsWrongSecret() {
        var provider = new PasswordAuthenticationProvider("user", "unit-test-secret-only");
        var valid = new dev.forge.auth.Credentials("user", "unit-test-secret-only".toCharArray(), Map.of());
        var invalid = new dev.forge.auth.Credentials("user", "incorrect".toCharArray(), Map.of());
        try { assertTrue(provider.authenticate(valid).isPresent()); assertTrue(provider.authenticate(invalid).isEmpty()); }
        finally { valid.wipe(); invalid.wipe(); }
    }

    @Test public void childWorkspaceCwdAndActualFastTask() throws Exception {
        Path root = temporary.newFolder().toPath(); Files.createDirectory(root.resolve("project"));
        var workspaces = new LocalWorkspaceProvider(root, 1024 * 1024, 100, 1000);
        var workspace = workspaces.discover().stream().filter(w -> w.location().path().equals("project")).findFirst().orElseThrow();
        workspaces.open(workspace.id()); EventBus events = new EventBus();
        var terminals = new TerminalService(new ProcessTerminalProvider(workspaces, true), events, "/bin/sh");
        TaskProvider taskProvider = new TaskProvider() {
            public String id() { return "test"; }
            public List<Task> provide(WorkspaceId id) { return List.of(new Task("hello", "Hello", "test", "/bin/echo", List.of("hello"), "", Map.of(), false, "test")); }
        };
        var tasks = new TaskService(List.of(taskProvider), terminals, events); terminals.start(); tasks.start();
        try {
            var task = tasks.run(workspace.id(), "hello");
            await(() -> tasks.require(task.id(), workspace.id()).state() != dev.forge.tasks.State.RUNNING);
            assertEquals(dev.forge.tasks.State.SUCCEEDED, tasks.require(task.id(), workspace.id()).state());
            assertTrue(terminals.scrollback(task.terminalId(), workspace.id()).contains("hello"));
            var pwd = terminals.run(workspace.id(), "/bin/pwd", List.of(), "", "pwd", Map.of());
            await(() -> terminals.consumeExitCode(pwd.id()).isPresent());
            assertEquals(root.resolve("project").toRealPath().toString(), terminals.scrollback(pwd.id(), workspace.id()).strip());
        } finally { tasks.dispose(); terminals.dispose(); workspaces.close(workspace.id()); }
    }

    @Test public void subprocessEnvironmentHasOnlyAllowlistedNames() throws Exception {
        var result = Processes.run(temporary.newFolder().toPath(), Duration.ofSeconds(3), List.of("/usr/bin/env"));
        assertTrue(result.ok());
        Set<String> allowed = Set.of("PATH", "HOME", "LANG", "GIT_CONFIG_NOSYSTEM", "GIT_CONFIG_GLOBAL", "GIT_TERMINAL_PROMPT", "GIT_ASKPASS");
        for (String line : result.output().lines().toList()) assertTrue(allowed.contains(line.substring(0, line.indexOf('='))));
    }

    @Test public void gitLiteralPathsAndStatusPreserveSpaces() throws Exception {
        Path root = temporary.newFolder().toPath();
        Processes.run(root, Duration.ofSeconds(3), List.of("git", "init")).orThrow("Init");
        Files.writeString(root.resolve("literal[1].txt"), "one"); Files.writeString(root.resolve("literal1.txt"), "two");
        Files.writeString(root.resolve(" spaced .txt"), "space");
        var workspaces = new LocalWorkspaceProvider(root, 1024 * 1024, 100, 1000);
        var workspace = workspaces.discover().stream().filter(w -> w.location().path().isEmpty()).findFirst().orElseThrow();
        workspaces.open(workspace.id());
        try {
            var git = new GitSourceControlProvider(workspaces); git.stage(workspace.id(), List.of("literal[1].txt"));
            var status = git.status(workspace.id());
            assertTrue(status.changes().stream().anyMatch(c -> c.path().equals("literal[1].txt") && c.staged()));
            assertFalse(status.changes().stream().anyMatch(c -> c.path().equals("literal1.txt") && c.staged()));
            assertTrue(status.changes().stream().anyMatch(c -> c.path().equals(" spaced .txt")));
        } finally { workspaces.close(workspace.id()); }
    }

    @Test public void workspaceGitconfigCannotExecuteFsmonitorDuringStatus() throws Exception {
        Path root = temporary.newFolder().toPath();
        Processes.run(root, Duration.ofSeconds(3), List.of("git", "init")).orThrow("Init");
        Path marker = root.resolve("fsmonitor-ran");
        Path helper = root.resolve("fsmonitor-helper.sh");
        Files.writeString(helper, "#!/bin/sh\ntouch \"" + marker + "\"\nexit 0\n");
        helper.toFile().setExecutable(true);
        Files.writeString(root.resolve(".gitconfig"), "[core]\n\tfsmonitor = " + helper + "\n");

        var workspaces = new LocalWorkspaceProvider(root, 1024 * 1024, 100, 1000);
        var workspace = workspaces.discover().stream().filter(w -> w.location().path().isEmpty()).findFirst().orElseThrow();
        workspaces.open(workspace.id());
        try {
            var git = new GitSourceControlProvider(workspaces);
            git.status(workspace.id());
            assertFalse("workspace-controlled global git config must not execute fsmonitor", Files.exists(marker));
        } finally { workspaces.close(workspace.id()); }
    }

    @Test public void stageDoesNotExecuteRepositoryConfiguredCleanFilter() throws Exception {
        Path root = temporary.newFolder().toPath();
        Processes.run(root, Duration.ofSeconds(3), List.of("git", "init")).orThrow("Init");
        Path marker = root.resolve("filter-ran");
        Path helper = root.resolve("clean-filter.sh");
        Files.writeString(helper, "#!/bin/sh\ntouch \"" + marker + "\"\ncat\n");
        helper.toFile().setExecutable(true);
        Files.writeString(root.resolve(".gitattributes"), "victim.txt filter=forgeevil\n");
        Files.writeString(root.resolve("victim.txt"), "payload\n");
        Processes.run(root, Duration.ofSeconds(3),
                List.of("git", "config", "--local", "filter.forgeevil.clean", helper.toString())).orThrow("Configure filter");

        var workspaces = new LocalWorkspaceProvider(root, 1024 * 1024, 100, 1000);
        var workspace = workspaces.discover().stream().filter(w -> w.location().path().isEmpty()).findFirst().orElseThrow();
        workspaces.open(workspace.id());
        try {
            var git = new GitSourceControlProvider(workspaces);
            try {
                git.stage(workspace.id(), List.of("victim.txt"));
                fail("staging a path with an active Git filter must be rejected");
            } catch (ForgeException expected) {
                assertFalse("repository-configured clean filter must not execute", Files.exists(marker));
            }
        } finally { workspaces.close(workspace.id()); }
    }

    private void await(java.util.function.BooleanSupplier ready) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!ready.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("Process did not finish within five seconds");
            Thread.sleep(10);
        }
    }
}
