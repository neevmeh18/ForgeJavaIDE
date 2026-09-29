package dev.forge.language;

import dev.forge.core.WorkspaceId;
import dev.forge.core.event.Event;
import dev.forge.language.Diagnostic;
import java.util.List;

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
