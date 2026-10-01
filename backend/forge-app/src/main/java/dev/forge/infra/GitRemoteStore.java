package dev.forge.infra;

import dev.forge.core.ForgeException;
import dev.forge.core.Ids.WorkspaceId;
import dev.forge.state.StateStore;
import java.util.LinkedHashMap;
import java.util.Map;

public final class GitRemoteStore {

    private static final String STORE_OWNER = "git-remotes";

    private final StateStore store;

    public GitRemoteStore(StateStore store) {
        this.store = store;
    }

    public void bind(WorkspaceId workspace, String repositoryUrl) {
        Map<String, Object> document = new LinkedHashMap<>(
                store.read(StateStore.Scope.USER, STORE_OWNER));
        document.put(workspace.value(), GitCredentialStore.normalize(repositoryUrl));
        store.write(StateStore.Scope.USER, STORE_OWNER, document);
    }

    public String require(WorkspaceId workspace) {
        Object value = store.read(StateStore.Scope.USER, STORE_OWNER).get(workspace.value());
        if (!(value instanceof String repositoryUrl)) {
            throw ForgeException.conflict("Repository remote is not registered with Forge");
        }
        return GitCredentialStore.normalize(repositoryUrl);
    }
}
