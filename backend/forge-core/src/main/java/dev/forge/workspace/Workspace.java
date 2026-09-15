package dev.forge.workspace;

import dev.forge.core.Ids.WorkspaceId;
import java.time.Instant;
import java.util.Map;

/**
 * A development environment the IDE is operating on.
 *
 * <p>The workspace is the framework's central concept, and its identity is <em>not</em> its
 * physical location. {@link Location} is opaque metadata owned by whichever
 * {@link WorkspaceProvider} produced the workspace; no feature interprets it. That is what lets
 * the same editor, search and source-control code run against a directory on a laptop, a volume
 * in a container, a checkout over SSH or a cloud-hosted environment.
 */
public record Workspace(
        WorkspaceId id,
        String name,
        Location location,
        Map<String, String> metadata,
        State state,
        Instant openedAt) {

    public enum State {
        /** Known to a provider but not loaded. */
        AVAILABLE,
        OPENING,
        OPEN,
        CLOSED,
        ERROR
    }

    /**
     * Where a workspace lives, in provider terms. {@code scheme} selects the provider
     * ({@code local}, {@code container}, {@code ssh}, …); the rest is that provider's business.
     */
    public record Location(String scheme, String authority, String path) {
        public static Location local(String path) {
            return new Location("local", "", path);
        }

        @Override
        public String toString() {
            return scheme + "://" + authority + (path.startsWith("/") ? path : "/" + path);
        }
    }

    public Workspace {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public Workspace withState(State newState) {
        return new Workspace(id, name, location, metadata, newState,
                newState == State.OPEN ? Instant.now() : openedAt);
    }
}
