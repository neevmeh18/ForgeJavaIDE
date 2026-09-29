package dev.forge.debug;

import java.util.List;
import java.util.Map;

public record DebugConfiguration(String name, String type, String request, Map<String, Object> options) {
    public DebugConfiguration {
        options = options == null ? Map.of() : Map.copyOf(options);
    }
}
