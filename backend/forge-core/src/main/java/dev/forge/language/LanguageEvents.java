package dev.forge.language;

import dev.forge.core.Ids.WorkspaceId;
import dev.forge.core.event.Event;
import dev.forge.language.LanguageTypes.Diagnostic;
import java.util.List;

/** Language tooling occurrences. */
public final class LanguageEvents {

    private LanguageEvents() {
    }

    /**
     * Diagnostics are pushed, not polled: a language server reports problems when its analysis
     * finishes, which may be long after the edit that caused them.
     */
    public record DiagnosticsPublished(WorkspaceId workspaceId, String path, List<Diagnostic> diagnostics)
            implements Event {

        public DiagnosticsPublished {
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }

        @Override
        public String type() {
            return "language.diagnostics";
        }
    }

    public record LanguageProviderReady(WorkspaceId workspaceId, String providerId, List<String> languages)
            implements Event {
        @Override
        public String type() {
            return "language.providerReady";
        }
    }
}
