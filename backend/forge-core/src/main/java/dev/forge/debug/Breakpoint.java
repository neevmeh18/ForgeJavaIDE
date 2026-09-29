package dev.forge.debug;

import java.util.List;
import java.util.Map;

public record Breakpoint(String id, String path, int line, boolean enabled, String condition, boolean verified) {
}
