package dev.forge.terminal;

import dev.forge.core.ForgeException;
import dev.forge.core.Ids;
import dev.forge.core.TerminalId;
import dev.forge.core.WorkspaceId;
import dev.forge.core.Lifecycle;
import dev.forge.core.Log;
import dev.forge.core.event.EventBus;
import dev.forge.filesystem.Resource;
import dev.forge.workspace.WorkspaceEvents;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public record TerminalInfo(TerminalId id, WorkspaceId workspaceId, String title, String cwd, boolean alive) { }
