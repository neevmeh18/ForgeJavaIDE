package dev.forge.core.extension;

import dev.forge.core.Args;
import dev.forge.core.ForgeException;
import dev.forge.core.ExtensionId;
import dev.forge.core.WorkspaceId;
import dev.forge.core.Lifecycle;
import dev.forge.core.Log;
import dev.forge.core.RequestContext;
import dev.forge.core.command.CommandDescriptor;
import dev.forge.core.command.CommandExecution;
import dev.forge.core.command.CommandExecutor;
import dev.forge.core.command.CommandHandler;
import dev.forge.core.command.CommandId;
import dev.forge.core.command.CommandInterceptor;
import dev.forge.core.command.CommandRegistry;
import dev.forge.core.contrib.ContributionRegistry;
import dev.forge.core.event.Event;
import dev.forge.core.event.EventBus;
import dev.forge.core.query.QueryRegistry;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public record ExtensionEvent(String type, ExtensionId extensionId, WorkspaceId workspaceId,
                             Map<String, Object> payload) implements Event {
}
