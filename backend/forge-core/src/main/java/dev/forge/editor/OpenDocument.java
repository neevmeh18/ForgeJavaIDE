package dev.forge.editor;

import dev.forge.core.ForgeException;
import dev.forge.core.Ids;
import dev.forge.core.DocumentId;
import dev.forge.core.SessionId;
import dev.forge.core.WorkspaceId;
import dev.forge.core.Lifecycle;
import dev.forge.core.event.EventBus;
import dev.forge.filesystem.FileEvents;
import dev.forge.filesystem.FileService;
import dev.forge.filesystem.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public record OpenDocument(Document document, String text) { }
