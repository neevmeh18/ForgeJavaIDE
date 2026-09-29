package dev.forge.debug;

import java.util.List;
import java.util.Map;

public record Variable(String name, String value, String type, int childrenReference) {
}
