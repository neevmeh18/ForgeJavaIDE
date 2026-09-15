package dev.forge.core;

import java.util.Map;

/**
 * The framework's structured failure type.
 *
 * <p>Failures are never signalled with {@code null}, {@code false} or a bare string. Every
 * failure carries a machine-readable {@link Code} that transports can translate (to an HTTP
 * status, a JSON-RPC error, a CLI exit code) without inspecting the message text.
 *
 * <p>The {@link #details()} map is for structured, <em>non-sensitive</em> context that helps a
 * caller react (which resource, which command). Stack traces never cross a transport boundary.
 */
public class ForgeException extends RuntimeException {

    public enum Code {
        NOT_FOUND,
        INVALID_ARGUMENT,
        CONFLICT,
        UNAUTHORIZED,
        FORBIDDEN,
        UNAVAILABLE,
        UNSUPPORTED,
        CANCELLED,
        INTERNAL_FAILURE
    }

    private final Code code;
    private final Map<String, String> details;

    public ForgeException(Code code, String message, Map<String, String> details, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public Code code() {
        return code;
    }

    public Map<String, String> details() {
        return details;
    }

    public ForgeException with(String key, String value) {
        Map<String, String> merged = new java.util.LinkedHashMap<>(details);
        merged.put(key, value);
        return new ForgeException(code, getMessage(), merged, getCause());
    }

    public static ForgeException notFound(String message) {
        return new ForgeException(Code.NOT_FOUND, message, null, null);
    }

    public static ForgeException invalidArgument(String message) {
        return new ForgeException(Code.INVALID_ARGUMENT, message, null, null);
    }

    public static ForgeException conflict(String message) {
        return new ForgeException(Code.CONFLICT, message, null, null);
    }

    public static ForgeException unauthorized(String message) {
        return new ForgeException(Code.UNAUTHORIZED, message, null, null);
    }

    public static ForgeException forbidden(String message) {
        return new ForgeException(Code.FORBIDDEN, message, null, null);
    }

    public static ForgeException unavailable(String message) {
        return new ForgeException(Code.UNAVAILABLE, message, null, null);
    }

    public static ForgeException unsupported(String message) {
        return new ForgeException(Code.UNSUPPORTED, message, null, null);
    }

    public static ForgeException cancelled(String message) {
        return new ForgeException(Code.CANCELLED, message, null, null);
    }

    public static ForgeException internal(String message, Throwable cause) {
        return new ForgeException(Code.INTERNAL_FAILURE, message, null, cause);
    }

    /**
     * Normalises an arbitrary throwable into a {@code ForgeException}. Unknown failures become
     * {@code INTERNAL_FAILURE} with a generic message: the original is kept as the cause for
     * server-side logging but its text is not promoted into something a client will read.
     */
    public static ForgeException normalize(Throwable t) {
        Throwable unwrapped = t;
        while ((unwrapped instanceof java.util.concurrent.CompletionException
                || unwrapped instanceof java.util.concurrent.ExecutionException)
                && unwrapped.getCause() != null) {
            unwrapped = unwrapped.getCause();
        }
        if (unwrapped instanceof ForgeException forge) {
            return forge;
        }
        if (unwrapped instanceof java.util.concurrent.CancellationException) {
            return cancelled("Operation cancelled");
        }
        if (unwrapped instanceof IllegalArgumentException) {
            return invalidArgument(unwrapped.getMessage() == null ? "Invalid argument" : unwrapped.getMessage());
        }
        if (unwrapped instanceof java.nio.file.NoSuchFileException e) {
            return notFound("No such resource: " + e.getFile());
        }
        if (unwrapped instanceof java.nio.file.FileAlreadyExistsException e) {
            return conflict("Already exists: " + e.getFile());
        }
        if (unwrapped instanceof java.nio.file.AccessDeniedException) {
            return forbidden("Access denied");
        }
        if (unwrapped instanceof UnsupportedOperationException) {
            return unsupported(unwrapped.getMessage() == null ? "Unsupported operation" : unwrapped.getMessage());
        }
        return internal("Internal failure", unwrapped);
    }
}
