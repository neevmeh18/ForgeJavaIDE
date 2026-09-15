package dev.forge.infra;

import dev.forge.language.LanguageProvider;
import dev.forge.language.LanguageTypes.CompletionItem;
import dev.forge.language.LanguageTypes.CompletionKind;
import dev.forge.language.LanguageTypes.DocumentSnapshot;
import dev.forge.language.LanguageTypes.Position;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Word completion drawn from the document being edited.
 *
 * <p>Not language intelligence, and not pretending to be: it exists so the language pipeline is
 * exercised end to end out of the box — a real provider registers alongside it and its results
 * are merged in. Registering an LSP-backed provider requires no change here or anywhere else.
 *
 * <p>Serves every language via the {@code "*"} wildcard.
 */
public final class BufferWordLanguageProvider implements LanguageProvider {

    private static final Pattern WORD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{2,}");
    private static final int MAX_SUGGESTIONS = 50;

    @Override
    public String id() {
        return "buffer-words";
    }

    @Override
    public Set<String> languages() {
        return Set.of("*");
    }

    @Override
    public List<CompletionItem> completion(DocumentSnapshot document, Position position) {
        String prefix = prefixAt(document.text(), position);
        Map<String, Integer> counts = new HashMap<>();
        Matcher matcher = WORD.matcher(document.text());
        while (matcher.find()) {
            String word = matcher.group();
            if (!word.equals(prefix)) {
                counts.merge(word, 1, Integer::sum);
            }
        }
        return counts.entrySet().stream()
                .filter(entry -> prefix.isEmpty()
                        || entry.getKey().regionMatches(true, 0, prefix, 0, prefix.length()))
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(MAX_SUGGESTIONS)
                .map(entry -> new CompletionItem(entry.getKey(), CompletionKind.TEXT,
                        entry.getValue() + " occurrences in this file", null, entry.getKey(), entry.getKey()))
                .sorted(Comparator.comparing(CompletionItem::label))
                .toList();
    }

    /** The identifier fragment immediately before the cursor. */
    private static String prefixAt(String text, Position position) {
        String[] lines = text.split("\n", -1);
        if (position.line() < 0 || position.line() >= lines.length) {
            return "";
        }
        String line = lines[position.line()];
        int end = Math.clamp(position.character(), 0, line.length());
        int start = end;
        while (start > 0 && (Character.isLetterOrDigit(line.charAt(start - 1)) || line.charAt(start - 1) == '_')) {
            start--;
        }
        return line.substring(start, end);
    }
}
