package dev.forge.core;

import dev.forge.core.Ids.DebugSessionId;
import dev.forge.core.Ids.DocumentId;
import dev.forge.core.Ids.TaskExecutionId;
import dev.forge.core.Ids.TerminalId;
import dev.forge.core.Ids.WorkspaceId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Typed, framework-neutral access to command and query arguments.
 *
 * <p>Arguments are restricted to JSON-shaped values: string, number, boolean, list, map, null.
 * Nothing from a transport or a UI toolkit ever gets this far — there is no way to hand a
 * {@code MouseEvent}, a Monaco model or an HTTP request object to application logic.
 *
 * <p>Accessors validate as they read, so handlers do not each re-implement argument checking:
 * a missing or mistyped argument produces {@code INVALID_ARGUMENT} with the offending name.
 */
public record Args(Map<String, Object> values) {

    public static final Args EMPTY = new Args(Map.of());

    public Args {
        values = values == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(values));
    }

    public static Args of(Map<String, Object> values) {
        return new Args(values);
    }

    public static Args of(String k1, Object v1) {
        return new Args(mapOfNullable(k1, v1));
    }

    public Optional<String> string(String name) {
        Object value = values.get(name);
        if (value == null) {
            return Optional.empty();
        }
        if (!(value instanceof String s)) {
            throw ForgeException.invalidArgument("Argument '" + name + "' must be a string");
        }
        return Optional.of(s);
    }

    public String requiredString(String name) {
        return string(name).orElseThrow(() -> ForgeException.invalidArgument("Missing argument '" + name + "'"));
    }

    public Optional<Integer> integer(String name) {
        Object value = values.get(name);
        if (value == null) {
            return Optional.empty();
        }
        if (!(value instanceof Number n)) {
            throw ForgeException.invalidArgument("Argument '" + name + "' must be an integer");
        }
        double asDouble = n.doubleValue();
        long asLong = n.longValue();
        if (!Double.isFinite(asDouble) || asDouble != asLong
                || asLong < Integer.MIN_VALUE || asLong > Integer.MAX_VALUE) {
            throw ForgeException.invalidArgument("Argument '" + name + "' must be a 32-bit integer");
        }
        return Optional.of((int) asLong);
    }

    public Optional<Long> longInteger(String name) {
        Object value = values.get(name);
        if (value == null) {
            return Optional.empty();
        }
        if (!(value instanceof Number n)) {
            throw ForgeException.invalidArgument("Argument '" + name + "' must be an integer");
        }
        double asDouble = n.doubleValue();
        long asLong = n.longValue();
        if (!Double.isFinite(asDouble) || asDouble != asLong) {
            throw ForgeException.invalidArgument("Argument '" + name + "' must be an integer");
        }
        return Optional.of(asLong);
    }

    public long longInteger(String name, long fallback) {
        return longInteger(name).orElse(fallback);
    }

    public int integer(String name, int fallback) {
        return integer(name).orElse(fallback);
    }

    public boolean bool(String name, boolean fallback) {
        Object value = values.get(name);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Boolean b)) {
            throw ForgeException.invalidArgument("Argument '" + name + "' must be a boolean");
        }
        return b;
    }

    @SuppressWarnings("unchecked")
    public List<Object> list(String name) {
        Object value = values.get(name);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> l)) {
            throw ForgeException.invalidArgument("Argument '" + name + "' must be a list");
        }
        return (List<Object>) l;
    }

    public List<String> strings(String name) {
        return list(name).stream()
                .map(item -> {
                    if (!(item instanceof String s)) {
                        throw ForgeException.invalidArgument("Argument '" + name + "' must be a list of strings");
                    }
                    return s;
                })
                .toList();
    }

    @SuppressWarnings("unchecked")
    public Args nested(String name) {
        Object value = values.get(name);
        if (value == null) {
            return EMPTY;
        }
        if (!(value instanceof Map<?, ?> m)) {
            throw ForgeException.invalidArgument("Argument '" + name + "' must be an object");
        }
        return new Args((Map<String, Object>) m);
    }

    /** The raw value, for the rare handler that accepts free-form structured data. */
    public Object raw(String name) {
        return values.get(name);
    }

    public WorkspaceId workspaceId(String name) {
        return WorkspaceId.of(requiredString(name));
    }

    public DocumentId documentId(String name) {
        return DocumentId.of(requiredString(name));
    }

    public TerminalId terminalId(String name) {
        return TerminalId.of(requiredString(name));
    }

    public TaskExecutionId taskExecutionId(String name) {
        return TaskExecutionId.of(requiredString(name));
    }

    public DebugSessionId debugSessionId(String name) {
        return DebugSessionId.of(requiredString(name));
    }

    private static Map<String, Object> mapOfNullable(String key, Object value) {
        return value == null ? Map.of() : Map.of(key, value);
    }
}
