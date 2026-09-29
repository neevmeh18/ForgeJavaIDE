package dev.forge.language;

import dev.forge.core.DocumentId;
import dev.forge.core.WorkspaceId;
import java.util.List;

public record Diagnostic(Range range, Severity severity, String message, String source, String code) {
}
