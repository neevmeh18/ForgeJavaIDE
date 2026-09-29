package dev.forge.language;

import dev.forge.core.DocumentId;
import dev.forge.core.WorkspaceId;
import java.util.List;

public record CompletionItem(
        String label,
        CompletionKind kind,
        String detail,
        String documentation,
        String insertText,
        String sortText) {
}
