package dev.forge.settings;

import dev.forge.core.ForgeException;
import dev.forge.core.Ids.ExtensionId;
import dev.forge.core.Ids.UserId;
import dev.forge.core.Ids.WorkspaceId;
import dev.forge.core.Lifecycle.Disposable;
import dev.forge.core.event.EventBus;
import dev.forge.state.StateStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Declares settings and resolves them through their layers.
 *
 * <p>Resolution walks {@code WORKSPACE > USER > EXTENSION > DEFAULT} and reports which layer
 * won, so a settings UI can show "overridden in this workspace" instead of a bare value.
 *
 * <p>Persistence is borrowed from {@link StateStore} rather than reinvented: user settings are a
 * user-scoped document, workspace settings a workspace-scoped one. Swapping the state
 * implementation for a database moves settings with it and changes nothing here.
 */
public final class SettingsService {

    private static final String DOCUMENT_KEY = "settings";

    private final Map<String, Settings.Definition> definitions = new ConcurrentHashMap<>();
    private final StateStore state;
    private final EventBus events;

    public SettingsService(StateStore state, EventBus events) {
        this.state = state;
        this.events = events;
    }

    public Disposable define(Settings.Definition definition) {
        if (definitions.putIfAbsent(definition.key(), definition) != null) {
            throw ForgeException.conflict("Setting already defined: " + definition.key());
        }
        return () -> definitions.remove(definition.key());
    }

    /** Declares a setting on behalf of an extension; its default becomes the EXTENSION layer. */
    public Disposable define(ExtensionId extension, Settings.Definition definition) {
        return define(new Settings.Definition(definition.key(), definition.type(), definition.defaultValue(),
                definition.description(), Settings.Layer.EXTENSION, definition.allowedValues(),
                extension.value()));
    }

    public List<Settings.Definition> definitions() {
        return definitions.values().stream().sorted(Comparator.comparing(Settings.Definition::key)).toList();
    }

    /** Every setting with its effective value for this user and workspace. */
    public List<Settings.Resolved> resolveAll(UserId user, WorkspaceId workspace) {
        Map<String, Object> userLayer = layer(StateStore.Scope.USER, user == null ? null : user.value());
        Map<String, Object> workspaceLayer =
                layer(StateStore.Scope.WORKSPACE, workspace == null ? null : workspace.value());
        List<Settings.Resolved> resolved = new ArrayList<>();
        for (Settings.Definition definition : definitions()) {
            resolved.add(resolve(definition, userLayer, workspaceLayer));
        }
        return resolved;
    }

    public Settings.Resolved resolve(String key, UserId user, WorkspaceId workspace) {
        Settings.Definition definition = require(key);
        return resolve(definition,
                layer(StateStore.Scope.USER, user == null ? null : user.value()),
                layer(StateStore.Scope.WORKSPACE, workspace == null ? null : workspace.value()));
    }

    /** Convenience for features that want one typed value and a fallback. */
    public <T> T value(String key, UserId user, WorkspaceId workspace, Class<T> type, T fallback) {
        Object value = resolve(key, user, workspace).value();
        return type.isInstance(value) ? type.cast(value) : fallback;
    }

    /**
     * Writes a value into one layer. {@code DEFAULT} and {@code EXTENSION} are read-only —
     * they come from code and manifests, not from users.
     */
    public synchronized Settings.Resolved set(String key, Object rawValue, Settings.Layer layer,
                                 UserId user, WorkspaceId workspace) {
        Settings.Definition definition = require(key);
        Object value = definition.coerce(rawValue);
        StateStore.Scope scope = switch (layer) {
            case USER -> StateStore.Scope.USER;
            case WORKSPACE -> StateStore.Scope.WORKSPACE;
            case DEFAULT, EXTENSION -> throw ForgeException.forbidden(
                    "The " + layer + " settings layer is read-only");
        };
        String owner = layer == Settings.Layer.USER
                ? requireOwner(user == null ? null : user.value(), "user")
                : requireOwner(workspace == null ? null : workspace.value(), "workspace");

        Map<String, Object> document = new LinkedHashMap<>(readDocument(scope, owner));
        if (value == null) {
            document.remove(key);
        } else {
            document.put(key, value);
        }
        state.put(scope, owner, DOCUMENT_KEY, document);
        events.publish(new SettingsEvents.SettingChanged(workspace, key, layer, value));
        return resolve(key, user, workspace);
    }

    private Settings.Resolved resolve(Settings.Definition definition,
                                      Map<String, Object> userLayer, Map<String, Object> workspaceLayer) {
        String key = definition.key();
        if (workspaceLayer.containsKey(key)) {
            return new Settings.Resolved(key, definition.coerce(workspaceLayer.get(key)),
                    Settings.Layer.WORKSPACE, definition);
        }
        if (userLayer.containsKey(key)) {
            return new Settings.Resolved(key, definition.coerce(userLayer.get(key)),
                    Settings.Layer.USER, definition);
        }
        return new Settings.Resolved(key, definition.defaultValue(), definition.defaultLayer(), definition);
    }

    private Settings.Definition require(String key) {
        Settings.Definition definition = definitions.get(key);
        if (definition == null) {
            throw ForgeException.notFound("Unknown setting: " + key).with("settingKey", key);
        }
        return definition;
    }

    private Map<String, Object> layer(StateStore.Scope scope, String owner) {
        return owner == null ? Map.of() : readDocument(scope, owner);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readDocument(StateStore.Scope scope, String owner) {
        Object document = state.get(scope, owner, DOCUMENT_KEY).orElse(null);
        return document instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static String requireOwner(String owner, String what) {
        return Optional.ofNullable(owner)
                .orElseThrow(() -> ForgeException.invalidArgument("No " + what + " in context"));
    }
}
