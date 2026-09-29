package dev.forge.language;

import dev.forge.core.WorkspaceId;
import dev.forge.core.event.Event;
import dev.forge.language.Diagnostic;
import java.util.List;

public record LanguageProviderReady(WorkspaceId workspaceId, String providerId, List<String> languages)
        implements Event {
    @Override
    public String type() {
        return "language.providerReady";
    }
}
