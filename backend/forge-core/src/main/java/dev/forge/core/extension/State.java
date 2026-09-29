package dev.forge.core.extension;

import dev.forge.core.ForgeException;
import dev.forge.core.ExtensionId;
import dev.forge.core.Lifecycle;
import dev.forge.core.Log;
import dev.forge.core.command.CommandExecutor;
import dev.forge.core.command.CommandRegistry;
import dev.forge.core.contrib.ContributionRegistry;
import dev.forge.core.event.EventBus;
import dev.forge.core.query.QueryRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public enum State {
    DISCOVERED,
    LOADED,
    ACTIVATED,
    DEACTIVATED,
    FAILED
}
