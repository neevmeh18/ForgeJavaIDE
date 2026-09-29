package dev.forge.settings;

import dev.forge.core.WorkspaceId;
import dev.forge.core.event.Event;

public record SettingChanged(WorkspaceId workspaceId, String key, dev.forge.settings.Layer layer, Object value)
        implements Event {
    @Override
    public String type() {
        return "settings.changed";
    }
}
