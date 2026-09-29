package dev.forge.tasks;

import dev.forge.core.ForgeException;
import dev.forge.core.Ids;
import dev.forge.core.TaskExecutionId;
import dev.forge.core.TerminalId;
import dev.forge.core.WorkspaceId;
import dev.forge.core.Lifecycle;
import dev.forge.core.Log;
import dev.forge.core.event.EventBus;
import dev.forge.terminal.TerminalEvents;
import dev.forge.terminal.TerminalService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public enum State { RUNNING, SUCCEEDED, FAILED, CANCELLED }
