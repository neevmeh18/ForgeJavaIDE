package dev.forge.terminal;

import dev.forge.core.TerminalId;
import dev.forge.core.WorkspaceId;








public interface TerminalSession {

    TerminalId id();

    WorkspaceId workspaceId();


    void write(String data);

    void resize(int columns, int rows);


    void kill();

    boolean isAlive();
}
