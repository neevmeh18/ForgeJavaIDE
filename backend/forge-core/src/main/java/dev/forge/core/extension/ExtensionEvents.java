package dev.forge.core.extension;

import dev.forge.core.Ids.ExtensionId;
import dev.forge.core.event.Event;

/** Lifecycle events for extensions, so a product can surface activation problems in its UI. */
public final class ExtensionEvents {

    private ExtensionEvents() {
    }

    public record ExtensionActivated(ExtensionId extensionId, String name) implements Event {
        @Override
        public String type() {
            return "extension.activated";
        }
    }

    public record ExtensionDeactivated(ExtensionId extensionId) implements Event {
        @Override
        public String type() {
            return "extension.deactivated";
        }
    }

    /** Carries a sanitised reason: an extension's stack trace stays in the server log. */
    public record ExtensionFailed(ExtensionId extensionId, String phase, String reason) implements Event {
        @Override
        public String type() {
            return "extension.failed";
        }
    }
}
