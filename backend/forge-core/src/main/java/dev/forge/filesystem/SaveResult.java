package dev.forge.filesystem;

import dev.forge.core.ForgeException;
import dev.forge.core.WorkspaceId;
import dev.forge.core.Disposable;
import dev.forge.core.Log;
import dev.forge.core.event.EventBus;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;

public record SaveResult(String path, long size, long modifiedAt) {
}
