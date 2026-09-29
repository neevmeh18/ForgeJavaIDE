package dev.forge.core.extension;

import dev.forge.core.ExtensionId;
import dev.forge.core.event.Event;

public record ExtensionDeactivated(ExtensionId extensionId) implements Event {
    @Override
    public String type() {
        return "extension.deactivated";
    }
}
