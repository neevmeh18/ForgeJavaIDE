package dev.forge.filesystem;

import dev.forge.core.WorkspaceId;
import dev.forge.core.Disposable;
import java.util.List;
import java.util.function.Consumer;














public interface FileSystem {












    Stat stat(String path);

    boolean exists(String path);


    List<Entry> list(String path);


    byte[] read(String path, long maxBytes);

    void write(String path, byte[] content);

    void createDirectory(String path);

    void createFile(String path);

    void delete(String path, boolean recursive);

    void move(String from, String to);

    void copy(String from, String to);





    Disposable watch(String path, boolean recursive, Consumer<Change> listener);





    default boolean isAvailable() {
        return true;
    }
}
