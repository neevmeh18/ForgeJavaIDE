package dev.forge.filesystem;

import dev.forge.core.WorkspaceId;
import dev.forge.core.Disposable;
import java.util.List;
import java.util.function.Consumer;

public enum ChangeKind {
    CREATED,
    CHANGED,
    DELETED
}
