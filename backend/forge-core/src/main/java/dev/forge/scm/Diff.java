package dev.forge.scm;

import java.time.Instant;
import java.util.List;

public record Diff(String path, String text, boolean staged) {
}
