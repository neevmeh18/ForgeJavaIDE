package dev.forge.language;

import dev.forge.core.Ids.DocumentId;
import dev.forge.core.Ids.WorkspaceId;
import java.util.List;

/**
 * The vocabulary of language tooling: positions, edits, completions, diagnostics, symbols.
 *
 * <p>Modelled on the shapes the Language Server Protocol uses, because that is what real
 * language servers speak — but expressed in the framework's own types so that neither the
 * editor nor any feature ends up importing an LSP library, and a provider that is not an LSP
 * server fits just as well.
 *
 * <p>These are plain values in one file: fifteen records, fifteen files and no extra boundary
 * would be a worse trade.
 */
public final class LanguageTypes {

    private LanguageTypes() {
    }

    /** Zero-based, as every editor protocol worth matching uses. */
    public record Position(int line, int character) {
    }

    public record Range(Position start, Position end) {
    }

    public record TextEdit(Range range, String newText) {
    }

    /** What a provider is asked about: the current buffer, not the file on disk. */
    public record DocumentSnapshot(
            WorkspaceId workspaceId,
            DocumentId documentId,
            String path,
            String languageId,
            String text,
            int version) {
    }

    public enum CompletionKind {
        TEXT, METHOD, FUNCTION, CONSTRUCTOR, FIELD, VARIABLE, CLASS, INTERFACE,
        MODULE, PROPERTY, KEYWORD, SNIPPET, FILE, REFERENCE
    }

    public record CompletionItem(
            String label,
            CompletionKind kind,
            String detail,
            String documentation,
            String insertText,
            String sortText) {
    }

    public record Hover(String contents, Range range) {
    }

    /** A location in the workspace. {@code path} is workspace-relative, never absolute. */
    public record Location(String path, Range range) {
    }

    public enum Severity {
        ERROR, WARNING, INFORMATION, HINT
    }

    public record Diagnostic(Range range, Severity severity, String message, String source, String code) {
    }

    public enum SymbolKind {
        FILE, MODULE, NAMESPACE, PACKAGE, CLASS, METHOD, PROPERTY, FIELD, CONSTRUCTOR,
        ENUM, INTERFACE, FUNCTION, VARIABLE, CONSTANT, STRUCT
    }

    public record SymbolInfo(String name, SymbolKind kind, String path, Range range, String container) {
    }

    public record CodeAction(String title, String kind, List<TextEdit> edits) {
        public CodeAction {
            edits = edits == null ? List.of() : List.copyOf(edits);
        }
    }

    /** A rename result: edits grouped by the file they apply to. */
    public record WorkspaceEdit(List<FileEdits> changes) {
        public WorkspaceEdit {
            changes = changes == null ? List.of() : List.copyOf(changes);
        }
    }

    public record FileEdits(String path, List<TextEdit> edits) {
        public FileEdits {
            edits = edits == null ? List.of() : List.copyOf(edits);
        }
    }
}
