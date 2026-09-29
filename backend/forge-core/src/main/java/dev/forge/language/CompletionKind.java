package dev.forge.language;

import dev.forge.core.DocumentId;
import dev.forge.core.WorkspaceId;
import java.util.List;

public enum CompletionKind {
    TEXT, METHOD, FUNCTION, CONSTRUCTOR, FIELD, VARIABLE, CLASS, INTERFACE,
    MODULE, PROPERTY, KEYWORD, SNIPPET, FILE, REFERENCE
}
