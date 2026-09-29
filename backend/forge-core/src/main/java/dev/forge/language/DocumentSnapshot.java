package dev.forge.language;

import dev.forge.core.DocumentId;
import dev.forge.core.WorkspaceId;
import java.util.List;

public record DocumentSnapshot(
        WorkspaceId workspaceId,
        DocumentId documentId,
        String path,
        String languageId,
        String text,
        int version) {
}
