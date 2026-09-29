package dev.forge.filesystem;

import dev.forge.core.ForgeException;
import dev.forge.core.WorkspaceId;













public record Resource(WorkspaceId workspace, String path) {

    private static final int MAX_PATH_LENGTH = 4096;

    public Resource {
        path = normalize(path);
    }

    public static Resource of(WorkspaceId workspace, String path) {
        return new Resource(workspace, path);
    }


    public static Resource root(WorkspaceId workspace) {
        return new Resource(workspace, "");
    }

    public boolean isRoot() {
        return path.isEmpty();
    }


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
