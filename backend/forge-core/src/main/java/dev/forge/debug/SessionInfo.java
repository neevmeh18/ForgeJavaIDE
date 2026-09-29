package dev.forge.debug;

import java.util.List;
import java.util.Map;

public record SessionInfo(String id, String name, String type, SessionState state, List<ThreadInfo> threads) {
    public SessionInfo {
        threads = threads == null ? List.of() : List.copyOf(threads);
    }
}
