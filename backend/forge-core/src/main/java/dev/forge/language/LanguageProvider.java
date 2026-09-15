package dev.forge.language;

import dev.forge.core.Ids.WorkspaceId;
import dev.forge.language.LanguageTypes.CodeAction;
import dev.forge.language.LanguageTypes.CompletionItem;
import dev.forge.language.LanguageTypes.DocumentSnapshot;
import dev.forge.language.LanguageTypes.Hover;
import dev.forge.language.LanguageTypes.Location;
import dev.forge.language.LanguageTypes.Position;
import dev.forge.language.LanguageTypes.Range;
import dev.forge.language.LanguageTypes.SymbolInfo;
import dev.forge.language.LanguageTypes.TextEdit;
import dev.forge.language.LanguageTypes.WorkspaceEdit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Language intelligence for one or more languages.
 *
 * <p>Every capability has a default that returns nothing, so a provider implements only what it
 * can actually do and gains new capabilities without breaking. The framework is
 * language-independent — Java is what it is written in, not what it supports — so nothing here
 * mentions a specific language, and an LSP-backed provider, a built-in analyser and a
 * heuristic completer all satisfy the same interface.
 */
public interface LanguageProvider {

    String id();

    /** Language ids this provider serves, as produced by {@code Document.languageFor}. */
    Set<String> languages();

    default List<CompletionItem> completion(DocumentSnapshot document, Position position) {
        return List.of();
    }

    default Optional<Hover> hover(DocumentSnapshot document, Position position) {
        return Optional.empty();
    }

    default List<Location> definition(DocumentSnapshot document, Position position) {
        return List.of();
    }

    default List<Location> references(DocumentSnapshot document, Position position) {
        return List.of();
    }

    default List<TextEdit> format(DocumentSnapshot document) {
        return List.of();
    }

    default Optional<WorkspaceEdit> rename(DocumentSnapshot document, Position position, String newName) {
        return Optional.empty();
    }

    default List<SymbolInfo> documentSymbols(DocumentSnapshot document) {
        return List.of();
    }

    default List<SymbolInfo> workspaceSymbols(WorkspaceId workspace, String query) {
        return List.of();
    }

    default List<CodeAction> codeActions(DocumentSnapshot document, Range range) {
        return List.of();
    }

    /**
     * Document synchronisation. Providers that keep their own copy of the buffer (any real
     * language server does) need these; the rest ignore them.
     */
    default void documentOpened(DocumentSnapshot document) {
    }

    default void documentChanged(DocumentSnapshot document) {
    }

    default void documentClosed(DocumentSnapshot document) {
    }

    /** Push diagnostics. Called once with a sink the provider may use at any time. */
    default void onDiagnostics(Consumer<LanguageEvents.DiagnosticsPublished> sink) {
    }

    default void dispose() {
    }
}
