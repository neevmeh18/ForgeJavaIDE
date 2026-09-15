package dev.forge.search;

import dev.forge.core.Cancellation;
import dev.forge.core.Ids.WorkspaceId;
import dev.forge.core.Log;
import dev.forge.filesystem.FileSystem;
import dev.forge.filesystem.Resource;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Workspace-scale search.
 *
 * <p>Runs entirely in the backend against the {@link FileSystem} capability. A browser-side
 * recursive scan would be hopeless over a remote or containerised workspace and would pull the
 * whole tree across the wire; doing it here means the same code serves a local folder and a
 * cloud volume.
 *
 * <p>Every traversal is bounded — by result count, by file size, by skipped directories — and
 * polls the caller's {@link Cancellation.Token}, so an over-broad query costs a moment rather
 * than the process.
 */
public final class SearchService {

    private static final Log log = Log.of(SearchService.class);

    /** Directories that are never worth walking and would dominate every result set. */
    private static final Set<String> SKIPPED = Set.of(
            ".git", ".hg", ".svn", "node_modules", "target", "build", "dist", "out",
            ".gradle", ".idea", ".vscode", "__pycache__", ".venv", "vendor");

    private static final long MAX_SEARCHABLE_BYTES = 2L * 1024 * 1024;

    public record FileMatch(String path, String name, int score) {
    }

    public record TextMatch(String path, int line, int column, String preview) {
    }

    public record TextSearchResult(List<TextMatch> matches, boolean truncated, int filesScanned) {
    }

    /** Symbol search is answered by language tooling; supplied at assembly time. */
    @FunctionalInterface
    public interface SymbolSource {
        List<Object> symbols(WorkspaceId workspace, String query, Cancellation.Token cancellation);

        SymbolSource NONE = (workspace, query, cancellation) -> List.of();
    }

    private final FileSystem.Locator locator;
    private final SymbolSource symbols;

    public SearchService(FileSystem.Locator locator, SymbolSource symbols) {
        this.locator = locator;
        this.symbols = symbols == null ? SymbolSource.NONE : symbols;
    }

    /** Filename search with subsequence matching, the behaviour a quick-open expects. */
    public List<FileMatch> findFiles(WorkspaceId workspace, String query, int limit, Cancellation.Token cancel) {
        String needle = query.toLowerCase(Locale.ROOT);
        List<FileMatch> matches = new ArrayList<>();
        walk(workspace, cancel, entry -> {
            if (entry.directory()) {
                return true;
            }
            int score = score(entry.name().toLowerCase(Locale.ROOT), entry.path().toLowerCase(Locale.ROOT), needle);
            if (score > 0) {
                matches.add(new FileMatch(entry.path(), entry.name(), score));
            }
            return matches.size() < limit * 20;
        });
        return matches.stream()
                .sorted(Comparator.comparingInt(FileMatch::score).reversed()
                        .thenComparing(FileMatch::path))
                .limit(limit)
                .toList();
    }

    /** Full-text search. {@code regex} switches from literal to pattern matching. */
    public TextSearchResult findText(WorkspaceId workspace, String query, boolean regex, boolean caseSensitive,
                                     int limit, Cancellation.Token cancel) {
        Pattern pattern = compile(query, regex, caseSensitive);
        FileSystem fs = locator.forWorkspace(workspace);
        List<TextMatch> matches = new ArrayList<>();
        int[] scanned = {0};
        boolean[] truncated = {false};

        walk(workspace, cancel, entry -> {
            if (entry.directory() || entry.size() > MAX_SEARCHABLE_BYTES) {
                return true;
            }
            scanned[0]++;
            try {
                byte[] bytes = fs.read(entry.path(), MAX_SEARCHABLE_BYTES);
                if (looksBinary(bytes)) {
                    return true;
                }
                String content = new String(bytes, StandardCharsets.UTF_8);
                int line = 1;
                for (String text : content.split("\n", -1)) {
                    Matcher matcher = pattern.matcher(text);
                    if (matcher.find()) {
                        matches.add(new TextMatch(entry.path(), line, matcher.start() + 1, preview(text)));
                        if (matches.size() >= limit) {
                            truncated[0] = true;
                            return false;
                        }
                    }
                    line++;
                }
            } catch (RuntimeException e) {
                log.with("path", entry.path()).debug("Skipped unreadable file during search");
            }
            return true;
        });
        return new TextSearchResult(List.copyOf(matches), truncated[0], scanned[0]);
    }

    public List<Object> findSymbols(WorkspaceId workspace, String query, Cancellation.Token cancel) {
        return symbols.symbols(workspace, query, cancel);
    }

    /** Breadth-first traversal that honours cancellation and skips build output directories. */
    private void walk(WorkspaceId workspace, Cancellation.Token cancel,
                      java.util.function.Predicate<FileSystem.Entry> visitor) {
        FileSystem fs = locator.forWorkspace(workspace);
        Deque<String> queue = new ArrayDeque<>();
        queue.add(Resource.root(workspace).path());
        while (!queue.isEmpty()) {
            cancel.throwIfCancelled();
            String directory = queue.poll();
            List<FileSystem.Entry> entries;
            try {
                entries = fs.list(directory);
            } catch (RuntimeException e) {
                continue;
            }
            for (FileSystem.Entry entry : entries) {
                cancel.throwIfCancelled();
                if (entry.directory() && SKIPPED.contains(entry.name())) {
                    continue;
                }
                if (!visitor.test(entry)) {
                    return;
                }
                if (entry.directory()) {
                    queue.add(entry.path());
                }
            }
        }
    }

    /** Subsequence score: consecutive and name-local matches rank above scattered path hits. */
    private static int score(String name, String path, String needle) {
        if (needle.isEmpty()) {
            return 1;
        }
        int score = matchScore(name, needle);
        if (score > 0) {
            return score + 100;
        }
        return matchScore(path, needle);
    }

    private static int matchScore(String haystack, String needle) {
        int index = 0;
        int score = 0;
        int streak = 0;
        for (char c : needle.toCharArray()) {
            int found = haystack.indexOf(c, index);
            if (found < 0) {
                return 0;
            }
            streak = found == index ? streak + 1 : 0;
            score += 1 + streak;
            index = found + 1;
        }
        return score;
    }

    private static Pattern compile(String query, boolean regex, boolean caseSensitive) {
        int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        try {
            return Pattern.compile(regex ? query : Pattern.quote(query), flags);
        } catch (PatternSyntaxException e) {
            throw dev.forge.core.ForgeException.invalidArgument("Invalid search pattern: " + e.getDescription());
        }
    }

    private static boolean looksBinary(byte[] bytes) {
        int probe = Math.min(bytes.length, 4000);
        for (int i = 0; i < probe; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }

    private static String preview(String line) {
        String trimmed = line.strip();
        return trimmed.length() > 240 ? trimmed.substring(0, 240) + "…" : trimmed;
    }
}
