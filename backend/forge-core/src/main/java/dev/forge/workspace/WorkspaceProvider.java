package dev.forge.workspace;

import dev.forge.core.WorkspaceId;
import dev.forge.filesystem.FileSystem;
import java.util.List;
import java.util.Map;
import java.util.Optional;









public interface WorkspaceProvider {


    String scheme();


    List<Workspace> discover();

    Optional<Workspace> find(WorkspaceId id);


    Workspace create(String name, Map<String, String> options);





    Workspace open(WorkspaceId id);

    void close(WorkspaceId id);


    FileSystem fileSystem(WorkspaceId id);
}
