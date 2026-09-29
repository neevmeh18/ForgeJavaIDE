package dev.forge.core.extension;

import dev.forge.core.ExtensionId;
import dev.forge.core.event.Event;

public record ExtensionActivated(ExtensionId extensionId, String name) implements Event {
    @Override
    public String type() {
        return "extension.activated";
    }
}
