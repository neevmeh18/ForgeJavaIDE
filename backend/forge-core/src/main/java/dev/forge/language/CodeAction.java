package dev.forge.language;

import dev.forge.core.DocumentId;
import dev.forge.core.WorkspaceId;
import java.util.List;

public record CodeAction(String title, String kind, List<TextEdit> edits) {
    public CodeAction {
        edits = edits == null ? List.of() : List.copyOf(edits);
    }
}
