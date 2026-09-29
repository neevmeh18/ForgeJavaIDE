package dev.forge.settings;

import dev.forge.core.ForgeException;
import java.util.List;

public record Resolved(String key, Object value, Layer layer, Definition definition) {
}
