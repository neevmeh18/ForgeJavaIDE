package dev.forge.filesystem;

import dev.forge.core.ForgeException;
import dev.forge.core.Ids.WorkspaceId;

/**
 * A file or directory, identified by workspace plus a workspace-relative path.
 *
 * <p>Deliberately not a {@code java.io.File}, a {@code Path} or a URL. Features exchange
 * {@code Resource} values, and only the filesystem provider behind a workspace knows whether
 * that resolves to a local directory, a container volume, an SSH host or object storage.
 *
 * <p><b>Security:</b> normalisation happens here, once, at construction. Segments are checked
 * for traversal ({@code ..}), absolute roots, backslashes, NUL bytes and Windows drive letters,
 * so no later code has to remember to sanitise. A provider still re-checks the resolved real
 * path before touching anything — this is the first line of defence, not the only one.
 */
public record Resource(WorkspaceId workspace, String path) {

    private static final int MAX_PATH_LENGTH = 4096;

    public Resource {
        path = normalize(path);
    }

    public static Resource of(WorkspaceId workspace, String path) {
        return new Resource(workspace, path);
    }

    /** The workspace root itself. */
    public static Resource root(WorkspaceId workspace) {
        return new Resource(workspace, "");
    }

    public boolean isRoot() {
        return path.isEmpty();
    }

    /** Last path segment, or the empty string at the root. */
    public String name() {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    public Resource parent() {
        int slash = path.lastIndexOf('/');
        return new Resource(workspace, slash < 0 ? "" : path.substring(0, slash));
    }

    public Resource child(String segment) {
        return new Resource(workspace, path.isEmpty() ? segment : path + "/" + segment);
    }

    /** Sibling with a different final segment — the shape {@code file.rename} needs. */
    public Resource withName(String newName) {
        if (newName.isBlank() || newName.contains("/") || newName.contains("\\")
                || newName.equals(".") || newName.equals("..")) {
            throw ForgeException.invalidArgument("Invalid name: " + newName);
        }
        return parent().child(newName);
    }

    @Override
    public String toString() {
        return workspace.value() + ":/" + path;
    }

    private static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        if (raw.length() > MAX_PATH_LENGTH) {
            throw ForgeException.invalidArgument("Path too long");
        }
        if (raw.indexOf('\0') >= 0) {
            throw ForgeException.invalidArgument("Path contains a NUL byte");
        }
        String candidate = raw.replace('\\', '/').trim();
        while (candidate.startsWith("/")) {
            candidate = candidate.substring(1);
        }
        if (candidate.length() > 1 && candidate.charAt(1) == ':') {
            throw ForgeException.invalidArgument("Absolute paths are not addressable: " + raw);
        }
        StringBuilder result = new StringBuilder();
        for (String segment : candidate.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                throw ForgeException.invalidArgument("Path escapes the workspace: " + raw);
            }
            if (!result.isEmpty()) {
                result.append('/');
            }
            result.append(segment);
        }
        return result.toString();
    }
}
