package dev.forge.debug;

import java.util.List;
import java.util.Map;

public record StackFrame(int id, String name, String path, int line, int column) {
}
