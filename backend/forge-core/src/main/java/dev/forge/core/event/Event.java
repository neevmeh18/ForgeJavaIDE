package dev.forge.core.event;

import dev.forge.core.SessionId;
import dev.forge.core.WorkspaceId;












public interface Event {


    String type();


    default WorkspaceId workspaceId() {
        return null;
    }


    default SessionId sessionId() {
        return null;
    }
}
