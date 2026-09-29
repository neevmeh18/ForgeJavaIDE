package dev.forge.workspace;

import dev.forge.core.WorkspaceId;
import java.time.Instant;
import java.util.Map;

public record Location(String scheme, String authority, String path) {
    public static Location local(String path) {
        return new Location("local", "", path);
    }

    @Override
    public String toString() {
        return scheme + "://" + authority + (path.startsWith("/") ? path : "/" + path);
    }
}
