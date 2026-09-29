package dev.forge.language;

import dev.forge.core.DocumentId;
import dev.forge.core.WorkspaceId;
import java.util.List;

public record WorkspaceEdit(List<FileEdits> changes) {
    public WorkspaceEdit {
        changes = changes == null ? List.of() : List.copyOf(changes);
    }
}
