package dev.forge.core.extension;

import dev.forge.core.ExtensionId;
import java.util.List;



















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
