package dev.forge.scm;

import java.time.Instant;
import java.util.List;

public enum ChangeStatus {
    ADDED,
    MODIFIED,
    DELETED,
    RENAMED,
    UNTRACKED,
    CONFLICTED
}
