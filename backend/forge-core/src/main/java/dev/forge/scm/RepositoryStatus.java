package dev.forge.scm;

import java.time.Instant;
import java.util.List;

public record RepositoryStatus(
        boolean repository,
        String branch,
        int ahead,
        int behind,
        boolean clean,
        List<Change> changes,
        boolean conflicted) {

    public static final RepositoryStatus NONE =
            new RepositoryStatus(false, "", -1, -1, true, List.of(), false);

    public RepositoryStatus {
        changes = changes == null ? List.of() : List.copyOf(changes);
    }
}
