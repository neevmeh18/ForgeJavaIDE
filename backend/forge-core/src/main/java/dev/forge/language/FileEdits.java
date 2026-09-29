package dev.forge.language;

import dev.forge.core.DocumentId;
import dev.forge.core.WorkspaceId;
import java.util.List;

public record FileEdits(String path, List<TextEdit> edits) {
    public FileEdits {
        edits = edits == null ? List.of() : List.copyOf(edits);
    }
}
