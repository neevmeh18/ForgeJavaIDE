package dev.forge.core;

import dev.forge.core.SessionId;
import dev.forge.core.UserId;
import dev.forge.core.WorkspaceId;
import java.util.Optional;

public enum Origin {
    UI,
    EXTENSION,
    CLI,
    AUTOMATION,
    SYSTEM
}
