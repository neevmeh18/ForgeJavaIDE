package dev.forge.core.extension;

import dev.forge.core.Ids.ExtensionId;
import java.util.List;

/**
 * An extension's manifest, as read from its {@code forge-extension.json}.
 *
 * <p>Activation is condition-driven, not eager: an IDE that activates everything at startup
 * pays for every installed extension on every launch. Supported conditions today:
 * <ul>
 *   <li>{@code onStartup} — use sparingly; the framework logs it as a cost</li>
 *   <li>{@code onCommand:<commandId>} — activate when someone tries to run the command</li>
 *   <li>{@code onLanguage:<languageId>}</li>
 *   <li>{@code onFileType:<extension>}</li>
 *   <li>{@code onWorkspace} — activate when any workspace opens</li>
 * </ul>
 *
 * <p>{@code contributes} carries declarations that need no code and therefore no activation —
 * setting definitions, today. It stays an opaque map here because interpreting it belongs to
 * the features concerned, not to the core: a manifest must not drag the settings feature into
 * the framework's core.
 */
public record ExtensionDescriptor(
        ExtensionId id,
        String name,
        String version,
        String mainClass,
        String description,
        List<String> activationEvents,
        java.util.Map<String, Object> contributes) {

    public static final String ON_STARTUP = "onStartup";
    public static final String ON_WORKSPACE = "onWorkspace";

    public ExtensionDescriptor {
        name = name == null || name.isBlank() ? id.value() : name;
        version = version == null ? "0.0.0" : version;
        description = description == null ? "" : description;
        activationEvents = activationEvents == null ? List.of() : List.copyOf(activationEvents);
        contributes = contributes == null ? java.util.Map.of() : java.util.Map.copyOf(contributes);
    }

    public static String onCommand(String commandId) {
        return "onCommand:" + commandId;
    }

    public static String onLanguage(String languageId) {
        return "onLanguage:" + languageId;
    }
}
