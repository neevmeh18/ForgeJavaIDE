package dev.forge.filesystem;

import dev.forge.core.WorkspaceId;
import dev.forge.core.Disposable;
import java.util.List;
import java.util.function.Consumer;

public record Entry(String name, String path, boolean directory, long size, long modifiedAt) {
}
