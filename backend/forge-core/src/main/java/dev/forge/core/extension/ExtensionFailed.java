package dev.forge.core.extension;

import dev.forge.core.ExtensionId;
import dev.forge.core.event.Event;

public record ExtensionFailed(ExtensionId extensionId, String phase, String reason) implements Event {
    @Override
    public String type() {
        return "extension.failed";
    }
}
