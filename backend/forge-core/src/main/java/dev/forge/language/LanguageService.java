package dev.forge.language;

import dev.forge.core.Cancellation;
import dev.forge.core.ForgeException;
import dev.forge.core.DocumentId;
import dev.forge.core.WorkspaceId;
import dev.forge.core.Lifecycle;
import dev.forge.core.Disposable;
import dev.forge.core.Log;
import dev.forge.core.event.EventBus;
import dev.forge.language.DocumentSnapshot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;












public final class LanguageService implements dev.forge.core.Component {

    private static final Log log = Log.of(LanguageService.class);




    private final List<LanguageProvider> providers = new CopyOnWriteArrayList<>();
    private final DocumentSource documents;
    private final EventBus events;

    public LanguageService(DocumentSource documents, EventBus events) {
        this.documents = documents;
        this.events = events;
    }

    @Override
    public void start() {
        log.with("providers", providers.size()).info("Language service ready");
    }

    @Override
    public void dispose() {
        providers.forEach(provider -> {
            try {
                provider.dispose();
            } catch (RuntimeException e) {
                log.with("provider", provider.id()).warn("Language provider disposal failed", e);
            }
        });
        providers.clear();
    }

    public Disposable register(LanguageProvider provider) {
        providers.add(provider);
        provider.onDiagnostics(events::publish);
        log.with("provider", provider.id()).with("languages", String.join(",", provider.languages()))
                .info("Language provider registered");
        return () -> providers.remove(provider);
    }

    public List<String> supportedLanguages() {
        return providers.stream().flatMap(provider -> provider.languages().stream()).distinct().sorted().toList();
    }


    public void documentChanged(DocumentSnapshot snapshot) {
        forEach(snapshot.languageId(), provider -> provider.documentChanged(snapshot));
    }

    public void documentOpened(DocumentSnapshot snapshot) {
        forEach(snapshot.languageId(), provider -> provider.documentOpened(snapshot));
    }

    public void documentClosed(DocumentSnapshot snapshot) {
        forEach(snapshot.languageId(), provider -> provider.documentClosed(snapshot));
    }

    public List<dev.forge.language.CompletionItem> completion(WorkspaceId workspace, DocumentId document,
                                                         dev.forge.language.Position position) {
        return collect(workspace, document, provider -> provider.completion(snapshot(workspace, document), position));
    }

    public Optional<dev.forge.language.Hover> hover(WorkspaceId workspace, DocumentId document,
                                               dev.forge.language.Position position) {
        DocumentSnapshot snapshot = snapshot(workspace, document);
        for (LanguageProvider provider : forLanguage(snapshot.languageId())) {
            Optional<dev.forge.language.Hover> hover =
                    guard(provider, () -> provider.hover(snapshot, position), Optional.empty());
            if (hover.isPresent()) {
                return hover;
            }
        }
        return Optional.empty();
    }

    public List<dev.forge.language.Location> definition(WorkspaceId workspace, DocumentId document,
                                                   dev.forge.language.Position position) {
        return collect(workspace, document, provider -> provider.definition(snapshot(workspace, document), position));
    }

    public List<dev.forge.language.Location> references(WorkspaceId workspace, DocumentId document,
                                                   dev.forge.language.Position position) {
        return collect(workspace, document, provider -> provider.references(snapshot(workspace, document), position));
    }

    public List<dev.forge.language.TextEdit> format(WorkspaceId workspace, DocumentId document) {
        DocumentSnapshot snapshot = snapshot(workspace, document);

        for (LanguageProvider provider : forLanguage(snapshot.languageId())) {
            List<dev.forge.language.TextEdit> edits = guard(provider, () -> provider.format(snapshot), List.of());
            if (!edits.isEmpty()) {
                return edits;
            }
        }
        return List.of();
    }

    public Optional<dev.forge.language.WorkspaceEdit> rename(WorkspaceId workspace, DocumentId document,
                                                        dev.forge.language.Position position, String newName) {
        DocumentSnapshot snapshot = snapshot(workspace, document);
        for (LanguageProvider provider : forLanguage(snapshot.languageId())) {
            Optional<dev.forge.language.WorkspaceEdit> edit =
                    guard(provider, () -> provider.rename(snapshot, position, newName), Optional.empty());
            if (edit.isPresent()) {
                return edit;
            }
        }
        return Optional.empty();
    }

    public List<dev.forge.language.SymbolInfo> documentSymbols(WorkspaceId workspace, DocumentId document) {
        return collect(workspace, document, provider -> provider.documentSymbols(snapshot(workspace, document)));
    }

    public List<dev.forge.language.CodeAction> codeActions(WorkspaceId workspace, DocumentId document,
                                                      dev.forge.language.Range range) {
        return collect(workspace, document, provider -> provider.codeActions(snapshot(workspace, document), range));
    }


    public List<Object> workspaceSymbols(WorkspaceId workspace, String query, Cancellation.Token cancellation) {
        List<Object> symbols = new ArrayList<>();
        for (LanguageProvider provider : List.copyOf(providers)) {
            cancellation.throwIfCancelled();
            symbols.addAll(guard(provider, () -> provider.workspaceSymbols(workspace, query), List.of()));
        }
        return symbols;
    }

    private DocumentSnapshot snapshot(WorkspaceId workspace, DocumentId document) {
        return documents.snapshot(workspace, document)
                .orElseThrow(() -> ForgeException.notFound("Document is not open: " + document));
    }

    private <T> List<T> collect(WorkspaceId workspace, DocumentId document,
                                Function<LanguageProvider, List<T>> call) {
        DocumentSnapshot snapshot = snapshot(workspace, document);
        Map<String, T> merged = new LinkedHashMap<>();
        for (LanguageProvider provider : forLanguage(snapshot.languageId())) {
            for (T item : guard(provider, () -> call.apply(provider), List.<T>of())) {
                merged.putIfAbsent(String.valueOf(item), item);
            }
        }
        return List.copyOf(merged.values());
    }


    private List<LanguageProvider> forLanguage(String languageId) {
        return providers.stream()
                .filter(provider -> provider.languages().contains(languageId)
                        || provider.languages().contains("*"))
                .toList();
    }

    private void forEach(String languageId, java.util.function.Consumer<LanguageProvider> action) {
        for (LanguageProvider provider : forLanguage(languageId)) {
            guard(provider, () -> {
                action.accept(provider);
                return Boolean.TRUE;
            }, Boolean.FALSE);
        }
    }






    private <T> T guard(LanguageProvider provider, java.util.function.Supplier<T> call, T fallback) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            log.with("provider", provider.id()).warn("Language provider call failed", e);
            return fallback;
        }
    }
}
