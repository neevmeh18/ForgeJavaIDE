package dev.forge.infra;

import dev.forge.core.ForgeException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Checks every existing component, including the configured root itself. */
final class SafePaths {
    private SafePaths() { }
    static void noLinks(Path path) {
        Path current = path.toAbsolutePath().normalize().getRoot();
        for (Path part : path.toAbsolutePath().normalize()) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current))
                throw ForgeException.forbidden("Symbolic links are not allowed in storage paths");
        }
    }
}
