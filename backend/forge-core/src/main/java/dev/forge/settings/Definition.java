package dev.forge.settings;

import dev.forge.core.ForgeException;
import java.util.List;

public record Definition(
        String key,
        Type type,
        Object defaultValue,
        String description,
        Layer defaultLayer,
        List<String> allowedValues,
        String source) {

    public Definition {
        allowedValues = allowedValues == null ? List.of() : List.copyOf(allowedValues);
    }

    public static Definition of(String key, Type type, Object defaultValue, String description) {
        return new Definition(key, type, defaultValue, description, Layer.DEFAULT, List.of(), "builtin");
    }

    public Definition choices(String... values) {
        return new Definition(key, type, defaultValue, description, defaultLayer, List.of(values), source);
    }


    public Object coerce(Object value) {
        if (value == null) {
            return null;
        }
        Object coerced = switch (type) {
            case STRING -> value instanceof String s ? s : reject(value);
            case BOOLEAN -> value instanceof Boolean b ? b : reject(value);
            case NUMBER -> value instanceof Number n ? n : reject(value);
            case STRING_LIST -> value instanceof List<?> list
                    ? list.stream().map(item -> item instanceof String s ? s : reject(item)).toList()
                    : reject(value);
        };
        if (!allowedValues.isEmpty() && !allowedValues.contains(String.valueOf(coerced))) {
            throw ForgeException.invalidArgument(
                    "Setting '" + key + "' must be one of " + String.join(", ", allowedValues));
        }
        return coerced;
    }

    private <T> T reject(Object value) {
        throw ForgeException.invalidArgument(
                "Setting '" + key + "' expects " + type.name().toLowerCase(java.util.Locale.ROOT)
                        + " but got " + value.getClass().getSimpleName());
    }
}
