package dev.forge.language;

import dev.forge.core.Cancellation;
import dev.forge.core.ForgeException;
import dev.forge.core.DocumentId;
import dev.forge.core.WorkspaceId;
import dev.forge.core.Lifecycle;
import dev.forge.core.Disposable;
import dev.forge.core.Log;
import dev.forge.core.event.EventBus;
import dev.forge.language.DocumentSnapshot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

@FunctionalInterface
public interface DocumentSource {
    Optional<DocumentSnapshot> snapshot(WorkspaceId workspace, DocumentId document);
}
