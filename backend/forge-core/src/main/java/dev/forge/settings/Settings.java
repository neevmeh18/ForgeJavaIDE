package dev.forge.settings;

import dev.forge.core.ForgeException;
import java.util.List;

/**
 * Setting definitions and the layering rules that resolve them.
 *
 * <p>A setting has to be declared before it can be set. That is what makes a settings UI, an
 * extension's contributions and validation possible at all — an untyped bag of strings can be
 * neither presented nor checked.
 */
public final class Settings {

    private Settings() {
    }

    /** Lowest to highest precedence. A value in a later layer wins. */
    public enum Layer {
        /** Shipped with the framework. */
        DEFAULT,
        /** Contributed by an extension's manifest or activation. */
        EXTENSION,
        /** Chosen by one identity; follows them between workspaces. */
        USER,
        /** Chosen for one environment; shared by every session in it. */
        WORKSPACE
    }

    public enum Type {
        STRING,
        NUMBER,
        BOOLEAN,
        STRING_LIST
    }

    /**
     * A declared setting. {@code source} is {@code builtin} or the contributing extension id,
     * which is how the settings UI groups entries and how deactivating an extension removes them.
     */
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

        /** Validates and normalises a candidate value against this definition. */
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

    /** One resolved setting: the effective value plus where it came from. */
    public record Resolved(String key, Object value, Layer layer, Definition definition) {
    }
}
