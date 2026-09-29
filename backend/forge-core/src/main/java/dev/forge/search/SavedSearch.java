package dev.forge.search;

import dev.forge.core.UserId;
import dev.forge.core.Lifecycle;
import dev.forge.core.WorkspaceId;

public record SavedSearch(String id, String query, boolean regex, boolean caseSensitive) {}
