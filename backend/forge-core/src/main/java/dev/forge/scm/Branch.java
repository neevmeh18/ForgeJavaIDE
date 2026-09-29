package dev.forge.scm;

import java.time.Instant;
import java.util.List;

public record Branch(String name, boolean current, boolean remote) {
}
