package dev.forge.settings;

import dev.forge.core.Ids.WorkspaceId;
import dev.forge.core.Ids.UserId;
import dev.forge.core.event.Event;

/** Settings changes, so open clients re-render without polling. */
public final class SettingsEvents {

    private SettingsEvents() {
    }

    public record SettingChanged(WorkspaceId workspaceId, String key, Settings.Layer layer, Object value,
                                 UserId userId) implements Event {

        public SettingChanged(WorkspaceId workspaceId, String key, Settings.Layer layer, Object value) {
            this(workspaceId, key, layer, value, null);
        }

        @Override
        public String type() {
            return "settings.changed";
        }
    }
}
