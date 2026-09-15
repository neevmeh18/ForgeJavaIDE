package dev.forge.editor;

import dev.forge.core.Ids.DocumentId;
import dev.forge.core.Ids.WorkspaceId;
import java.util.Map;

/**
 * An open text document, identified independently of the file it came from.
 *
 * <p>One document per (workspace, path) — not per editor and not per session. Two sessions
 * looking at the same file share the document, which is the precondition for shared editing,
 * remote cursors and follow mode later, and is already what lets language tooling reason about
 * "the current contents" rather than "someone's tab".
 *
 * <p>{@code version} increases on every accepted change and is what a client uses to detect
 * that its working copy has fallen behind.
 */
public record Document(
        DocumentId id,
        WorkspaceId workspaceId,
        String path,
        String languageId,
        int version,
        boolean dirty) {

    /**
     * Language identification from the file name. Intentionally a small table rather than a
     * detection framework: the framework is language-independent, and real language support
     * arrives as a {@code LanguageProvider}, not as more cases here.
     */
    private static final Map<String, String> BY_EXTENSION = Map.ofEntries(
            Map.entry("java", "java"), Map.entry("kt", "kotlin"), Map.entry("scala", "scala"),
            Map.entry("ts", "typescript"), Map.entry("tsx", "typescriptreact"),
            Map.entry("js", "javascript"), Map.entry("jsx", "javascriptreact"),
            Map.entry("json", "json"), Map.entry("py", "python"), Map.entry("rb", "ruby"),
            Map.entry("go", "go"), Map.entry("rs", "rust"), Map.entry("c", "c"),
            Map.entry("h", "c"), Map.entry("cpp", "cpp"), Map.entry("hpp", "cpp"),
            Map.entry("cs", "csharp"), Map.entry("php", "php"), Map.entry("sh", "shellscript"),
            Map.entry("yaml", "yaml"), Map.entry("yml", "yaml"), Map.entry("toml", "toml"),
            Map.entry("xml", "xml"), Map.entry("html", "html"), Map.entry("css", "css"),
            Map.entry("scss", "scss"), Map.entry("md", "markdown"), Map.entry("sql", "sql"),
            Map.entry("dockerfile", "dockerfile"), Map.entry("gradle", "groovy"));

    public static String languageFor(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase(java.util.Locale.ROOT);
        if (name.equals("dockerfile") || name.startsWith("dockerfile.")) {
            return "dockerfile";
        }
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? "plaintext" : BY_EXTENSION.getOrDefault(name.substring(dot + 1), "plaintext");
    }

    public Document changed(int newVersion) {
        return new Document(id, workspaceId, path, languageId, newVersion, true);
    }

    public Document saved() {
        return new Document(id, workspaceId, path, languageId, version, false);
    }
}
