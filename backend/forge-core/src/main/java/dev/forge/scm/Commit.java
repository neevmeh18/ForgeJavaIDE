package dev.forge.scm;

import java.time.Instant;
import java.util.List;

public record Commit(String id, String shortId, String message, String author, Instant date) {
}
