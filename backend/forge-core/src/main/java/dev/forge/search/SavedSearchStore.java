package dev.forge.search;

import dev.forge.core.UserId;
import dev.forge.core.Lifecycle;
import dev.forge.core.WorkspaceId;

public interface SavedSearchStore extends dev.forge.core.Disposable {


    String save(UserId userId, WorkspaceId workspaceId, String query, boolean regex, boolean caseSensitive);

    SavedSearch loadAndResolve(UserId userId, WorkspaceId workspaceId, String id);

    @Override
    default void dispose() {}
}
