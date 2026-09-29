package dev.forge.workspace;

import dev.forge.core.WorkspaceId;
import java.time.Instant;
import java.util.Map;

public enum State {

    AVAILABLE,
    OPENING,
    OPEN,
    CLOSED,
    ERROR
}
