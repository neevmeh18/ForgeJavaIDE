package dev.forge.language;

import dev.forge.core.DocumentId;
import dev.forge.core.WorkspaceId;
import java.util.List;

public record SymbolInfo(String name, SymbolKind kind, String path, Range range, String container) {
}
