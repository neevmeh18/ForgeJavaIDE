package dev.forge.infra;

import dev.forge.core.ForgeException;
import dev.forge.core.Ids.WorkspaceId;
import dev.forge.core.Log;
import dev.forge.scm.ScmTypes.Branch;
import dev.forge.scm.ScmTypes.Change;
import dev.forge.scm.ScmTypes.ChangeStatus;
import dev.forge.scm.ScmTypes.Commit;
import dev.forge.scm.ScmTypes.Diff;
import dev.forge.scm.ScmTypes.RepositoryStatus;
import dev.forge.scm.SourceControlProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Source control through the {@code git} command-line tool.
 *
 * <p>Shelling out rather than embedding a Git library is a deliberate dependency choice: the
 * binary is already present wherever developers work, it is the reference implementation, and
 * it keeps a large library out of the runtime image. Everything Git-specific — porcelain
 * parsing, refspec handling, argument shapes — stays inside this class, and the rest of the IDE
 * sees only {@code ScmTypes}.
 *
 * <p>Untrusted strings (paths, branch names, messages) are passed as separate argv entries after
 * {@code --}, and branch names are validated, so nothing a user types can become a Git option.
 */
public final class GitSourceControlProvider implements SourceControlProvider {

    private static final Log log = Log.of(GitSourceControlProvider.class);
    private static final Duration LOCAL_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration NETWORK_TIMEOUT = Duration.ofMinutes(5);
    private static final Pattern BRANCH = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]{0,199}");

    /** ASCII unit separator: a field delimiter that cannot appear in a commit subject. */
    private static final String SEP = String.valueOf((char) 0x1f);

    private final LocalWorkspaceProvider workspaces;

    public GitSourceControlProvider(LocalWorkspaceProvider workspaces) {
        this.workspaces = workspaces;
    }

    @Override
    public String id() {
        return "git";
    }

    @Override
    public boolean isRepository(WorkspaceId workspace) {
        return workspaces.directory(workspace)
                .map(directory -> Files.isDirectory(directory.resolve(".git"))
                        || Files.isRegularFile(directory.resolve(".git")))
                .orElse(false);
    }

    @Override
    public RepositoryStatus status(WorkspaceId workspace) {
        Path directory = repository(workspace);
        Processes.Result result = git(directory, LOCAL_TIMEOUT, "status", "--porcelain=v1", "-b");
        if (!result.ok()) {
            return RepositoryStatus.NONE;
        }
        List<Change> changes = new ArrayList<>();
        String branch = "";
        int ahead = -1;
        int behind = -1;
        boolean conflicted = false;

        for (String line : result.output().split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            if (line.startsWith("## ")) {
                String header = line.substring(3);
                branch = header.split("\\.\\.\\.")[0].split(" ")[0];
                ahead = number(header, "ahead ");
                behind = number(header, "behind ");
                continue;
            }
            if (line.length() < 4) {
                continue;
            }
            char index = line.charAt(0);
            char worktree = line.charAt(1);
            String path = line.substring(3).trim();
            String original = null;
            if (path.contains(" -> ")) {
                String[] parts = path.split(" -> ", 2);
                original = unquote(parts[0]);
                path = parts[1];
            }
            path = unquote(path);

            boolean bothModified = index == 'U' || worktree == 'U'
                    || (index == 'A' && worktree == 'A') || (index == 'D' && worktree == 'D');
            if (bothModified) {
                conflicted = true;
                changes.add(new Change(path, ChangeStatus.CONFLICTED, false, original));
                continue;
            }
            if (index == '?') {
                changes.add(new Change(path, ChangeStatus.UNTRACKED, false, null));
                continue;
            }
            if (index != ' ') {
                changes.add(new Change(path, statusOf(index), true, original));
            }
            if (worktree != ' ') {
                changes.add(new Change(path, statusOf(worktree), false, original));
            }
        }
        return new RepositoryStatus(true, branch, ahead, behind, changes.isEmpty(),
                List.copyOf(changes), conflicted);
    }

    @Override
    public void stage(WorkspaceId workspace, List<String> paths) {
        run(workspace, "Stage", List.of("add", "--"), paths);
    }

    @Override
    public void unstage(WorkspaceId workspace, List<String> paths) {
        run(workspace, "Unstage", List.of("restore", "--staged", "--"), paths);
    }

    @Override
    public void discard(WorkspaceId workspace, List<String> paths) {
        run(workspace, "Discard", List.of("restore", "--worktree", "--"), paths);
    }

    @Override
    public Commit commit(WorkspaceId workspace, String message, boolean amend) {
        Path directory = repository(workspace);
        List<String> command = new ArrayList<>(List.of(
                "-c", "user.name=" + identity(directory, "user.name", "Forge User"),
                "-c", "user.email=" + identity(directory, "user.email", "forge@localhost"),
                "commit", "-m", message));
        if (amend) {
            command.add("--amend");
        }
        git(directory, LOCAL_TIMEOUT, command.toArray(String[]::new)).orThrow("Commit");
        return history(workspace, "", 1).stream().findFirst().orElseThrow(
                () -> ForgeException.internal("Commit succeeded but could not be read back", null));
    }

    @Override
    public List<Branch> branches(WorkspaceId workspace) {
        Path directory = repository(workspace);
        List<Branch> branches = new ArrayList<>();
        Processes.Result local = git(directory, LOCAL_TIMEOUT,
                "branch", "--format=%(HEAD)" + SEP + "%(refname:short)");
        for (String line : local.output().split("\n")) {
            String[] parts = line.split(SEP);
            if (parts.length == 2 && !parts[1].isBlank()) {
                branches.add(new Branch(parts[1].trim(), "*".equals(parts[0].trim()), false));
            }
        }
        Processes.Result remote = git(directory, LOCAL_TIMEOUT, "branch", "-r", "--format=%(refname:short)");
        for (String line : remote.output().split("\n")) {
            if (!line.isBlank() && !line.contains("->")) {
                branches.add(new Branch(line.trim(), false, true));
            }
        }
        return List.copyOf(branches);
    }

    @Override
    public void checkout(WorkspaceId workspace, String branch, boolean create) {
        if (!BRANCH.matcher(branch).matches()) {
            throw ForgeException.invalidArgument("Invalid branch name");
        }
        Path directory = repository(workspace);
        String[] command = create
                ? new String[] {"checkout", "-b", branch}
                : new String[] {"checkout", branch};
        git(directory, LOCAL_TIMEOUT, command).orThrow("Checkout");
    }

    @Override
    public Diff diff(WorkspaceId workspace, String path, boolean staged) {
        Path directory = repository(workspace);
        List<String> command = new ArrayList<>(List.of("diff"));
        if (staged) {
            command.add("--cached");
        }
        command.add("--");
        command.addAll(checkedPaths(path.isBlank() ? List.of() : List.of(path)));
        return new Diff(path, git(directory, LOCAL_TIMEOUT, command.toArray(String[]::new)).output(), staged);
    }

    @Override
    public List<Commit> history(WorkspaceId workspace, String path, int limit) {
        Path directory = repository(workspace);
        String format = "--format=%H" + SEP + "%h" + SEP + "%s" + SEP + "%an" + SEP + "%aI";
        List<String> command = new ArrayList<>(List.of("log", "-n", String.valueOf(limit), format));
        if (!path.isBlank()) {
            command.add("--");
            command.addAll(checkedPaths(List.of(path)));
        }
        Processes.Result result = git(directory, LOCAL_TIMEOUT, command.toArray(String[]::new));
        List<Commit> commits = new ArrayList<>();
        for (String line : result.output().split("\n")) {
            String[] parts = line.split(SEP);
            if (parts.length == 5) {
                commits.add(new Commit(parts[0], parts[1], parts[2], parts[3], parseDate(parts[4])));
            }
        }
        return List.copyOf(commits);
    }

    @Override
    public void fetch(WorkspaceId workspace) {
        git(repository(workspace), NETWORK_TIMEOUT, "fetch", "--prune").orThrow("Fetch");
    }

    @Override
    public void pull(WorkspaceId workspace) {
        git(repository(workspace), NETWORK_TIMEOUT, "pull", "--ff-only").orThrow("Pull");
    }

    @Override
    public void push(WorkspaceId workspace) {
        git(repository(workspace), NETWORK_TIMEOUT, "push").orThrow("Push");
    }

    private void run(WorkspaceId workspace, String what, List<String> verb, List<String> paths) {
        if (paths.isEmpty()) {
            throw ForgeException.invalidArgument("No paths given to " + what.toLowerCase(java.util.Locale.ROOT));
        }
        List<String> command = new ArrayList<>(verb);
        command.addAll(checkedPaths(paths));
        git(repository(workspace), LOCAL_TIMEOUT, command.toArray(String[]::new)).orThrow(what);
    }

    private static Processes.Result git(Path directory, Duration timeout, String... arguments) {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(arguments));
        return Processes.run(directory, timeout, command);
    }

    private Path repository(WorkspaceId workspace) {
        return workspaces.directory(workspace)
                .orElseThrow(() -> ForgeException.unavailable("Workspace is not open locally"));
    }

    /** Refuses anything that could be read as an option rather than a path. */
    private static List<String> checkedPaths(List<String> paths) {
        for (String path : paths) {
            if (path.isBlank() || path.startsWith("-") || path.contains("..")) {
                throw ForgeException.invalidArgument("Invalid path: " + path);
            }
        }
        return paths;
    }

    private static ChangeStatus statusOf(char code) {
        return switch (code) {
            case 'A' -> ChangeStatus.ADDED;
            case 'D' -> ChangeStatus.DELETED;
            case 'R' -> ChangeStatus.RENAMED;
            case '?' -> ChangeStatus.UNTRACKED;
            default -> ChangeStatus.MODIFIED;
        };
    }

    private static int number(String header, String marker) {
        int at = header.indexOf(marker);
        if (at < 0) {
            return -1;
        }
        StringBuilder digits = new StringBuilder();
        for (int i = at + marker.length(); i < header.length() && Character.isDigit(header.charAt(i)); i++) {
            digits.append(header.charAt(i));
        }
        return digits.isEmpty() ? -1 : Integer.parseInt(digits.toString());
    }

    private static String unquote(String path) {
        String trimmed = path.trim();
        return trimmed.length() > 1 && trimmed.startsWith("\"") && trimmed.endsWith("\"")
                ? trimmed.substring(1, trimmed.length() - 1)
                : trimmed;
    }

    private static Instant parseDate(String iso) {
        try {
            return java.time.OffsetDateTime.parse(iso).toInstant();
        } catch (RuntimeException e) {
            return Instant.EPOCH;
        }
    }

    /** Uses the repository's own identity when it has one, a local default otherwise. */
    private String identity(Path directory, String key, String fallback) {
        Processes.Result result = git(directory, LOCAL_TIMEOUT, "config", "--get", key);
        String value = result.output().strip();
        if (!result.ok() || value.isEmpty()) {
            log.with("key", key).debug("No git identity configured; using a local default");
            return fallback;
        }
        return value;
    }
}
