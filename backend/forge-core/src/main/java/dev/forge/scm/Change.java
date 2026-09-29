package dev.forge.scm;

import java.time.Instant;
import java.util.List;

public record Change(String path, ChangeStatus status, boolean staged, String originalPath) {
}
