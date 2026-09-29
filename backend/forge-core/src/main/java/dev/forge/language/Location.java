package dev.forge.language;

import dev.forge.core.DocumentId;
import dev.forge.core.WorkspaceId;
import java.util.List;

public record Location(String path, Range range) {
}
