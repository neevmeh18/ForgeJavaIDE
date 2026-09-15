package dev.forge.terminal;

import dev.forge.core.Ids.TerminalId;
import dev.forge.core.Ids.WorkspaceId;

/**
 * A live terminal, wherever it is actually running.
 *
 * <p>Nothing here assumes the process is on the machine holding the browser — it may be in the
 * server container, on a remote host or in a pod. That is why input, resize and kill are
 * operations on this handle rather than something a client does directly.
 */
public interface TerminalSession {

    TerminalId id();

    WorkspaceId workspaceId();

    /** Sends input as typed. */
    void write(String data);

    void resize(int columns, int rows);

    /** Terminates the underlying process. Safe to call more than once. */
    void kill();

    boolean isAlive();
}
