package dev.forge.auth;

import dev.forge.core.ForgeException;
import dev.forge.core.SessionId;
import dev.forge.core.command.CommandDescriptor;
import dev.forge.core.command.CommandRegistry;
import dev.forge.core.query.QueryRegistry;
import dev.forge.core.query.QueryDescriptor;
import java.time.Instant;
import java.util.Optional;

public record CurrentUser(User user, SessionId sessionId, Instant expiresAt) {
}
