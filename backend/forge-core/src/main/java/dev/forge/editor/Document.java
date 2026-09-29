package dev.forge.editor;

import dev.forge.core.DocumentId;
import dev.forge.core.WorkspaceId;
import java.util.Map;












public record Document(
        DocumentId id,
        WorkspaceId workspaceId,
        String path,
        String languageId,
        int version,
        boolean dirty) {






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
