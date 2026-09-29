package dev.forge.core.command;

import dev.forge.core.ExtensionId;
import java.util.Optional;

@FunctionalInterface
public interface Availability {
    Optional<String> unavailableReason(CommandContext ctx);

    Availability ALWAYS = ctx -> Optional.empty();
}
