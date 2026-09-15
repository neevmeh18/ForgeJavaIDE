package dev.forge.language;

import dev.forge.core.Cancellation;
import dev.forge.core.ForgeException;
import dev.forge.core.Ids.DocumentId;
import dev.forge.core.Ids.WorkspaceId;
import dev.forge.core.Lifecycle;
import dev.forge.core.Lifecycle.Disposable;
import dev.forge.core.Log;
import dev.forge.core.event.EventBus;
import dev.forge.language.LanguageTypes.DocumentSnapshot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Routes language requests to whichever providers serve a document's language.
 *
 * <p>Results from several providers are merged rather than arbitrated: a workspace can have a
 * real language server for Java and a lightweight word-completer for everything else, and the
 * editor sees one list.
 *
 * <p>Document text is fetched through {@link DocumentSource}, a lambda supplied at assembly
 * time. That is what keeps this package free of any dependency on the editor feature while
 * still working against live buffers rather than stale files on disk.
 */
public final class LanguageService implements Lifecycle.Component {

    private static final Log log = Log.of(LanguageService.class);

    /** Supplies the current buffer for a document. Wired to the editor at bootstrap. */
    @FunctionalInterface
    public interface DocumentSource {
        Optional<DocumentSnapshot> snapshot(WorkspaceId workspace, DocumentId document);
    }

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

    /** Called by the editor (via the bootstrap wiring) whenever a shared buffer changes. */
    public void documentChanged(DocumentSnapshot snapshot) {
        forEach(snapshot.languageId(), provider -> provider.documentChanged(snapshot));
    }

    public void documentOpened(DocumentSnapshot snapshot) {
        forEach(snapshot.languageId(), provider -> provider.documentOpened(snapshot));
    }

    public void documentClosed(DocumentSnapshot snapshot) {
        forEach(snapshot.languageId(), provider -> provider.documentClosed(snapshot));
    }

    public List<LanguageTypes.CompletionItem> completion(WorkspaceId workspace, DocumentId document,
                                                         LanguageTypes.Position position) {
        return collect(workspace, document, provider -> provider.completion(snapshot(workspace, document), position));
    }

    public Optional<LanguageTypes.Hover> hover(WorkspaceId workspace, DocumentId document,
                                               LanguageTypes.Position position) {
        DocumentSnapshot snapshot = snapshot(workspace, document);
        for (LanguageProvider provider : forLanguage(snapshot.languageId())) {
            Optional<LanguageTypes.Hover> hover =
                    guard(provider, () -> provider.hover(snapshot, position), Optional.empty());
            if (hover.isPresent()) {
                return hover;
            }
        }
        return Optional.empty();
    }

    public List<LanguageTypes.Location> definition(WorkspaceId workspace, DocumentId document,
                                                   LanguageTypes.Position position) {
        return collect(workspace, document, provider -> provider.definition(snapshot(workspace, document), position));
    }

    public List<LanguageTypes.Location> references(WorkspaceId workspace, DocumentId document,
                                                   LanguageTypes.Position position) {
        return collect(workspace, document, provider -> provider.references(snapshot(workspace, document), position));
    }

    public List<LanguageTypes.TextEdit> format(WorkspaceId workspace, DocumentId document) {
        DocumentSnapshot snapshot = snapshot(workspace, document);
        // Formatting is one provider's job: merging two formatters' edits produces nonsense.
        for (LanguageProvider provider : forLanguage(snapshot.languageId())) {
            List<LanguageTypes.TextEdit> edits = guard(provider, () -> provider.format(snapshot), List.of());
            if (!edits.isEmpty()) {
                return edits;
            }
        }
        return List.of();
    }

    public Optional<LanguageTypes.WorkspaceEdit> rename(WorkspaceId workspace, DocumentId document,
                                                        LanguageTypes.Position position, String newName) {
        DocumentSnapshot snapshot = snapshot(workspace, document);
        for (LanguageProvider provider : forLanguage(snapshot.languageId())) {
            Optional<LanguageTypes.WorkspaceEdit> edit =
                    guard(provider, () -> provider.rename(snapshot, position, newName), Optional.empty());
            if (edit.isPresent()) {
                return edit;
            }
        }
        return Optional.empty();
    }

    public List<LanguageTypes.SymbolInfo> documentSymbols(WorkspaceId workspace, DocumentId document) {
        return collect(workspace, document, provider -> provider.documentSymbols(snapshot(workspace, document)));
    }

    public List<LanguageTypes.CodeAction> codeActions(WorkspaceId workspace, DocumentId document,
                                                      LanguageTypes.Range range) {
        return collect(workspace, document, provider -> provider.codeActions(snapshot(workspace, document), range));
    }

    /** Adapter for {@code search.symbols}; matches the search feature's {@code SymbolSource}. */
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

    /** A provider declaring {@code "*"} serves every language; the rest opt in by id. */
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

    /**
     * A misbehaving language provider degrades intelligence; it must not break the editor.
     * The failure is logged, the provider's contribution falls back to {@code fallback}, and
     * the remaining providers still get their turn.
     */
    private <T> T guard(LanguageProvider provider, java.util.function.Supplier<T> call, T fallback) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            log.with("provider", provider.id()).warn("Language provider call failed", e);
            return fallback;
        }
    }
}
