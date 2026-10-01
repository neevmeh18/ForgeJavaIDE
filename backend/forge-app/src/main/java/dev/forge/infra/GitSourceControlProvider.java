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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private static final Pattern UNSAFE_CONFIG_KEY = Pattern.compile(
            "^(?:include(?:if)?\\..*|"
                    + "core\\.(?:fsmonitor|sshcommand|gitproxy|alternaterefscommand|hookspath|worktree|editor)|"
                    + "credential\\..*|"
                    + "filter\\..*\\.(?:clean|smudge|process)|"
                    + "diff\\..*\\.(?:command|textconv)|"
                    + "merge\\..*\\.driver|"
                    + "gpg(?:\\..*)?\\.program|"
                    + "url\\..*\\.(?:insteadof|pushinsteadof)|"
                    + "http\\..*|"
                    + "remote\\..*\\.(?:proxy|proxyauthmethod|uploadpack|receivepack|vcs)|"
                    + "submodule\\..*\\.update|"
                    + "extensions\\.worktreeconfig)$",
            Pattern.CASE_INSENSITIVE);
    private static final int MAX_PATHS = 256;
    private static final int MAX_PATH_CHARS = 4096;

    /** ASCII unit separator: a field delimiter that cannot appear in a commit subject. */
    private static final String SEP = String.valueOf((char) 0x1f);

    private final LocalWorkspaceProvider workspaces;
    private final GitCredentialStore credentials;
    private final GitRemoteStore remotes;
    private final boolean allowInsecureGitHttp;

    public GitSourceControlProvider(LocalWorkspaceProvider workspaces) {
        this(workspaces, null, null, false);
    }

    public GitSourceControlProvider(LocalWorkspaceProvider workspaces, GitCredentialStore credentials) {
        this(workspaces, credentials, null, false);
    }

    public GitSourceControlProvider(LocalWorkspaceProvider workspaces, GitCredentialStore credentials,
            GitRemoteStore remotes) {
        this(workspaces, credentials, remotes, false);
    }

    public GitSourceControlProvider(LocalWorkspaceProvider workspaces, GitCredentialStore credentials,
            GitRemoteStore remotes, boolean allowInsecureGitHttp) {
        this.workspaces = workspaces;
        this.credentials = credentials;
        this.remotes = remotes;
        this.allowInsecureGitHttp = allowInsecureGitHttp;
    }

    @Override
    public String id() {
        return "git";
    }

    @Override
    public boolean isRepository(WorkspaceId workspace) {
        return workspaces.directory(workspace)
                .map(directory -> Files.isDirectory(directory.resolve(".git"), LinkOption.NOFOLLOW_LINKS)
                        && !Files.isSymbolicLink(directory.resolve(".git")))
                .orElse(false);
    }

    @Override
    public RepositoryStatus status(WorkspaceId workspace) {
        Path directory = repository(workspace);
        Processes.Result result = git(directory, LOCAL_TIMEOUT, "status", "--porcelain=v1", "-b", "-z");
        if (!result.ok()) {
            return RepositoryStatus.NONE;
        }
        List<Change> changes = new ArrayList<>();
        String branch = "";
        int ahead = -1;
        int behind = -1;
        boolean conflicted = false;

        String[] records = result.output().split("\\x00", -1);
        for (int record = 0; record < records.length; record++) {
            String line = records[record];
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
            String path = line.substring(3);
            String original = null;
            if (index == 'R' || index == 'C' || worktree == 'R' || worktree == 'C') {
                if (++record >= records.length) throw ForgeException.unavailable("Incomplete Git status output");
                original = records[record];
            }

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
        Path directory = repository(workspace);
        String remoteUrl = trustedRemote(workspace);
        networkGit(directory, remoteUrl, "Fetch", "fetch", "--prune", remoteUrl,
                "+refs/heads/*:refs/remotes/origin/*");
    }

    @Override
    public void pull(WorkspaceId workspace) {
        Path directory = repository(workspace);
        String remoteUrl = trustedRemote(workspace);
        String branch = currentBranch(directory);
        networkGit(directory, remoteUrl, "Pull", "fetch", remoteUrl,
                "+refs/heads/" + branch + ":refs/remotes/origin/" + branch);
        git(directory, LOCAL_TIMEOUT, "merge", "--ff-only", "refs/remotes/origin/" + branch)
                .orThrow("Pull");
    }

    @Override
    public void push(WorkspaceId workspace) {
        Path directory = repository(workspace);
        String remoteUrl = trustedRemote(workspace);
        String branch = currentBranch(directory);
        networkGit(directory, remoteUrl, "Push", "push", remoteUrl,
                "HEAD:refs/heads/" + branch);
    }

    public Path cloneRepository(Path targetDir, String remoteUrl, String branch) {
        String normalizedRemoteUrl = GitCredentialStore.normalize(remoteUrl);
        GitCredentialStore.Credential credential = credentials == null
                ? null
                : credentials.find(normalizedRemoteUrl).orElse(null);
        return cloneRepository(targetDir, normalizedRemoteUrl, branch, credential);
    }

    public Path cloneRepository(Path targetDir, String remoteUrl, String branch,
            GitCredentialStore.Credential credential) {
        String normalizedRemoteUrl = GitCredentialStore.normalize(remoteUrl);
        Path dir = targetDir.normalize();
        GitCredentialStore.Credential effectiveCredential = credential;
        if (effectiveCredential == null && credentials != null) {
            effectiveCredential = credentials.find(normalizedRemoteUrl).orElse(null);
        }
        List<String> args = new ArrayList<>(List.of("clone"));
        if (branch != null && !branch.isBlank()) {
            if (!BRANCH.matcher(branch).matches() || branch.contains("..")) {
                throw ForgeException.invalidArgument("Invalid branch name");
            }
            args.add("--branch");
            args.add(branch);
        }
        args.add(normalizedRemoteUrl);
        args.add(dir.toString());
        authenticatedGit(dir.getParent(), NETWORK_TIMEOUT, args, normalizedRemoteUrl, effectiveCredential)
                .orThrow("Clone");
        return dir;
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
        return git(directory, timeout, List.of(arguments), Map.of());
    }

    private static Processes.Result git(Path directory, Duration timeout, List<String> arguments) {
        return git(directory, timeout, arguments, Map.of());
    }

    private static Processes.Result git(Path directory, Duration timeout, List<String> arguments,
            Map<String, String> environment) {
        return git(directory, timeout, arguments, environment, List.of());
    }

    private static Processes.Result git(Path directory, Duration timeout, List<String> arguments,
            Map<String, String> environment, List<String> additionalConfig) {
        List<String> command = new ArrayList<>(List.of("git", "--literal-pathspecs"));
        List<String> config = new ArrayList<>(List.of(
                "core.quotePath=false",
                "core.hooksPath=/dev/null",
                "core.fsmonitor=false",
                "core.sshCommand=/usr/bin/false",
                "core.gitProxy=",
                "core.alternateRefsCommand=",
                "core.pager=cat",
                "pager.status=false",
                "pager.diff=false",
                "diff.external=",
                "credential.helper=",
                "commit.gpgSign=false",
                "tag.gpgSign=false",
                "push.gpgSign=false",
                "submodule.recurse=false",
                "fetch.recurseSubmodules=false",
                "push.recurseSubmodules=no",
                "protocol.ext.allow=never",
                "protocol.file.allow=never"));
        if (Files.isDirectory(directory.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
            config.add("core.worktree=" + directory.toAbsolutePath().normalize());
            config.add("core.bare=false");
        }
        config.addAll(additionalConfig);
        for (String value : config) {
            command.add("-c");
            command.add(value);
        }
        command.addAll(arguments);
        return Processes.run(directory, timeout, command, environment);
    }

    private void networkGit(Path directory, String remoteUrl, String what, String... arguments) {
        GitCredentialStore.Credential credential = credentials == null || remoteUrl.isBlank()
                ? null
                : credentials.find(remoteUrl).orElse(null);
        authenticatedGit(directory, NETWORK_TIMEOUT, List.of(arguments), remoteUrl, credential).orThrow(what);
    }

    private Processes.Result authenticatedGit(Path directory, Duration timeout,
            List<String> arguments, String remoteUrl, GitCredentialStore.Credential credential) {
        if (!allowInsecureGitHttp && remoteUrl.regionMatches(true, 0, "http://", 0, 7)) {
            throw ForgeException.invalidArgument("Git network operations require HTTPS");
        }
        List<String> networkConfig = List.of(
                "http.proxy=",
                "http.curloptResolve=",
                "http.extraHeader=",
                "http.cookieFile=",
                "http.saveCookies=false",
                "http.followRedirects=false",
                "http.sslVerify=true",
                "http." + remoteUrl + ".proxy=",
                "http." + remoteUrl + ".curloptResolve=",
                "http." + remoteUrl + ".extraHeader=",
                "http." + remoteUrl + ".cookieFile=",
                "http." + remoteUrl + ".saveCookies=false",
                "http." + remoteUrl + ".followRedirects=false",
                "http." + remoteUrl + ".sslVerify=true");
        if (credential == null) {
            return git(directory, timeout, arguments, Map.of(), networkConfig);
        }
        AskPass askPass = createAskPassHelper(credential);
        try {
            Map<String, String> environment = Map.of(
                    "GIT_ASKPASS", askPass.helper().toString(),
                    "GIT_ASKPASS_REQUIRE", "force",
                    "FORGE_GIT_AUTH_DIR", askPass.directory().toString());
            return git(directory, timeout, arguments, environment, networkConfig);
        } finally {
            deleteAskPassHelper(askPass);
        }
    }

    private record AskPass(Path directory, Path helper) { }

    private static AskPass createAskPassHelper(GitCredentialStore.Credential credential) {
        try {
            Path directory = Files.createTempDirectory("forge-git-auth-");
            setOwnerOnlyDirectory(directory);
            Path username = directory.resolve("username");
            Path secret = directory.resolve("secret");
            Path helper = directory.resolve("askpass.sh");
            createOwnerOnlyFile(username, credential.username());
            createOwnerOnlyFile(secret, credential.password());
            Files.writeString(helper, """
                    #!/bin/sh
                    case "$1" in
                      *[Uu]sername*) cat "$FORGE_GIT_AUTH_DIR/username"; printf '\n' ;;
                      *) cat "$FORGE_GIT_AUTH_DIR/secret"; printf '\n' ;;
                    esac
                    """, StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(helper, Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE));
            } catch (UnsupportedOperationException ignored) {
                if (!helper.toFile().setExecutable(true, true)) {
                    throw ForgeException.unavailable("Could not make Git authentication helper executable");
                }
            }
            return new AskPass(directory, helper);
        } catch (IOException e) {
            throw ForgeException.unavailable("Could not create Git authentication helper");
        }
    }

    private static void createOwnerOnlyFile(Path file, String value) throws IOException {
        try {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)));
        } catch (UnsupportedOperationException ignored) {
            Files.createFile(file);
        }
        Files.writeString(file, value, StandardCharsets.UTF_8);
    }

    private static void setOwnerOnlyDirectory(Path directory) throws IOException {
        try {
            Files.setPosixFilePermissions(directory, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException ignored) {
        }
    }

    private static void deleteAskPassHelper(AskPass askPass) {
        try (var paths = Files.walk(askPass.directory())) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            log.debug("Could not fully remove temporary Git authentication helper");
        }
    }

    private String trustedRemote(WorkspaceId workspace) {
        if (remotes == null) {
            throw ForgeException.conflict("Repository remote is not registered with Forge");
        }
        return remotes.require(workspace);
    }

    private static String currentBranch(Path directory) {
        Processes.Result result = git(directory, LOCAL_TIMEOUT, "symbolic-ref", "--short", "HEAD");
        String branch = result.ok() ? result.output().trim() : "";
        if (!BRANCH.matcher(branch).matches() || branch.contains("..")) {
            throw ForgeException.conflict("A local branch is required for this operation");
        }
        return branch;
    }

    private static void validateRepository(Path directory) {
        Path metadata = directory.resolve(".git");
        if (!Files.isDirectory(metadata, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(metadata)) {
            throw ForgeException.conflict("Workspace does not contain a supported Git repository");
        }
        Path configFile = metadata.resolve("config");
        if (Files.isSymbolicLink(configFile)) {
            throw ForgeException.conflict("Repository Git configuration is not supported");
        }
        Processes.Result result = git(directory, LOCAL_TIMEOUT, "config", "--local", "--no-includes",
                "--name-only", "--get-regexp", ".*");
        if (!result.ok()) {
            throw ForgeException.conflict("Repository Git configuration could not be validated");
        }
        for (String key : result.output().lines().map(String::trim).filter(value -> !value.isEmpty()).toList()) {
            if (UNSAFE_CONFIG_KEY.matcher(key).matches()) {
                throw ForgeException.conflict("Repository Git configuration is not allowed for source-control operations");
            }
        }
    }

    private Path repository(WorkspaceId workspace) {
        Path directory = workspaces.directory(workspace)
                .orElseThrow(() -> ForgeException.unavailable("Workspace is not open locally"));
        validateRepository(directory);
        return directory;
    }

    /** Refuses anything that could be read as an option rather than a path. */
    private static List<String> checkedPaths(List<String> paths) {
        if (paths.size() > MAX_PATHS) {
            throw ForgeException.invalidArgument("Too many paths in one source-control operation");
        }
        List<String> checked = new ArrayList<>(paths.size());
        for (String raw : paths) {
            if (raw == null || raw.isBlank() || raw.length() > MAX_PATH_CHARS || raw.indexOf('\0') >= 0) {
                throw ForgeException.invalidArgument("Invalid source-control path");
            }
            String path = raw.replace('\\', '/');
            if (path.startsWith("/") || path.startsWith("-") || path.equals("..")
                    || path.startsWith("../") || path.endsWith("/..") || path.contains("/../")
                    || (path.length() > 1 && path.charAt(1) == ':')) {
                throw ForgeException.invalidArgument("Invalid source-control path");
            }
            checked.add(path);
        }
        return List.copyOf(checked);
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
