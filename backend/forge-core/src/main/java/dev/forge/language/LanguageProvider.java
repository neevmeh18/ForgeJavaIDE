package dev.forge.language;

import dev.forge.core.WorkspaceId;
import dev.forge.language.CodeAction;
import dev.forge.language.CompletionItem;
import dev.forge.language.DocumentSnapshot;
import dev.forge.language.Hover;
import dev.forge.language.Location;
import dev.forge.language.Position;
import dev.forge.language.Range;
import dev.forge.language.SymbolInfo;
import dev.forge.language.TextEdit;
import dev.forge.language.WorkspaceEdit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;










public interface LanguageProvider {

    String id();


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





    default void documentOpened(DocumentSnapshot document) {
    }

    default void documentChanged(DocumentSnapshot document) {
    }

    default void documentClosed(DocumentSnapshot document) {
    }


    default void onDiagnostics(Consumer<dev.forge.language.DiagnosticsPublished> sink) {
    }

    default void dispose() {
    }
}
