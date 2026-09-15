package dev.forge.core.event;

import dev.forge.core.Ids.SessionId;
import dev.forge.core.Ids.WorkspaceId;

/**
 * Something that already happened.
 *
 * <p>Events are notifications, never requests: {@code FileSaved} reports a completed save, it
 * does not ask anyone to save. Code that wants something done calls a feature API or executes a
 * command — the event bus is not a back door around either.
 *
 * <p>The optional scope accessors let the transport deliver an event only to the sessions it
 * concerns. A record whose components are named {@code workspaceId} / {@code sessionId}
 * satisfies them automatically.
 */
public interface Event {

    /** Stable wire name, e.g. {@code workspace.opened}. */
    String type();

    /** The workspace the event belongs to, or {@code null} for application-wide events. */
    default WorkspaceId workspaceId() {
        return null;
    }

    /** Set only for events meant for one session, such as a terminal's output. */
    default SessionId sessionId() {
        return null;
    }
}
