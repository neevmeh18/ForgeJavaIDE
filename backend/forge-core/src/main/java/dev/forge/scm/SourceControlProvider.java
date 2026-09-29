package dev.forge.scm;

import dev.forge.core.WorkspaceId;
import dev.forge.scm.Branch;
import dev.forge.scm.Commit;
import dev.forge.scm.Diff;
import dev.forge.scm.RepositoryStatus;
import java.util.List;








public interface SourceControlProvider {

    String id();


    boolean isRepository(WorkspaceId workspace);

    RepositoryStatus status(WorkspaceId workspace);

    void stage(WorkspaceId workspace, List<String> paths);

    void unstage(WorkspaceId workspace, List<String> paths);


    void discard(WorkspaceId workspace, List<String> paths);

    Commit commit(WorkspaceId workspace, String message, boolean amend);

    List<Branch> branches(WorkspaceId workspace);

    void checkout(WorkspaceId workspace, String branch, boolean create);

    Diff diff(WorkspaceId workspace, String path, boolean staged);

    List<Commit> history(WorkspaceId workspace, String path, int limit);


    void fetch(WorkspaceId workspace);

    void pull(WorkspaceId workspace);

    void push(WorkspaceId workspace);
}
