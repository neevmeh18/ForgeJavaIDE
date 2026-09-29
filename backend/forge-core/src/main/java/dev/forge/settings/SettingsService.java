package dev.forge.settings;

import dev.forge.core.ForgeException;
import dev.forge.core.ExtensionId;
import dev.forge.core.UserId;
import dev.forge.core.WorkspaceId;
import dev.forge.core.Disposable;
import dev.forge.core.event.EventBus;
import dev.forge.state.StateStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;











public final class SettingsService {

    private static final String DOCUMENT_KEY = "settings";

    private final Map<String, dev.forge.settings.Definition> definitions = new ConcurrentHashMap<>();
    private final StateStore state;
    private final EventBus events;

    public SettingsService(StateStore state, EventBus events) {
        this.state = state;
        this.events = events;
    }

    public Disposable define(dev.forge.settings.Definition definition) {
        if (definitions.putIfAbsent(definition.key(), definition) != null) {
            throw ForgeException.conflict("Setting already defined: " + definition.key());
        }
        return () -> definitions.remove(definition.key());
    }


    public Disposable define(ExtensionId extension, dev.forge.settings.Definition definition) {
        return define(new dev.forge.settings.Definition(definition.key(), definition.type(), definition.defaultValue(),
                definition.description(), dev.forge.settings.Layer.EXTENSION, definition.allowedValues(),
                extension.value()));
    }

    public List<dev.forge.settings.Definition> definitions() {
        return definitions.values().stream().sorted(Comparator.comparing(dev.forge.settings.Definition::key)).toList();
    }


    public List<dev.forge.settings.Resolved> resolveAll(UserId user, WorkspaceId workspace) {
        Map<String, Object> userLayer = layer(dev.forge.state.Scope.USER, user == null ? null : user.value());
        Map<String, Object> workspaceLayer =
                layer(dev.forge.state.Scope.WORKSPACE, workspace == null ? null : workspace.value());
        List<dev.forge.settings.Resolved> resolved = new ArrayList<>();
        for (dev.forge.settings.Definition definition : definitions()) {
            resolved.add(resolve(definition, userLayer, workspaceLayer));
        }
        return resolved;
    }

    public dev.forge.settings.Resolved resolve(String key, UserId user, WorkspaceId workspace) {
        dev.forge.settings.Definition definition = require(key);
        return resolve(definition,
                layer(dev.forge.state.Scope.USER, user == null ? null : user.value()),
                layer(dev.forge.state.Scope.WORKSPACE, workspace == null ? null : workspace.value()));
    }


    public <T> T value(String key, UserId user, WorkspaceId workspace, Class<T> type, T fallback) {
        Object value = resolve(key, user, workspace).value();
        return type.isInstance(value) ? type.cast(value) : fallback;
    }





    public synchronized dev.forge.settings.Resolved set(String key, Object rawValue, dev.forge.settings.Layer layer,
                                 UserId user, WorkspaceId workspace) {
        dev.forge.settings.Definition definition = require(key);
        Object value = definition.coerce(rawValue);
        dev.forge.state.Scope scope = switch (layer) {
            case USER -> dev.forge.state.Scope.USER;
            case WORKSPACE -> dev.forge.state.Scope.WORKSPACE;
            case DEFAULT, EXTENSION -> throw ForgeException.forbidden(
                    "The " + layer + " settings layer is read-only");
        };
        String owner = layer == dev.forge.settings.Layer.USER
                ? requireOwner(user == null ? null : user.value(), "user")
                : requireOwner(workspace == null ? null : workspace.value(), "workspace");

        Map<String, Object> document = new LinkedHashMap<>(readDocument(scope, owner));
        if (value == null) {
            document.remove(key);
        } else {
            document.put(key, value);
        }
        state.put(scope, owner, DOCUMENT_KEY, document);
        events.publish(new dev.forge.settings.SettingChanged(workspace, key, layer, value));
        return resolve(key, user, workspace);
    }

    private dev.forge.settings.Resolved resolve(dev.forge.settings.Definition definition,
                                      Map<String, Object> userLayer, Map<String, Object> workspaceLayer) {
        String key = definition.key();
        if (workspaceLayer.containsKey(key)) {
            return new dev.forge.settings.Resolved(key, definition.coerce(workspaceLayer.get(key)),
                    dev.forge.settings.Layer.WORKSPACE, definition);
        }
        if (userLayer.containsKey(key)) {
            return new dev.forge.settings.Resolved(key, definition.coerce(userLayer.get(key)),
                    dev.forge.settings.Layer.USER, definition);
        }
        return new dev.forge.settings.Resolved(key, definition.defaultValue(), definition.defaultLayer(), definition);
    }

    private dev.forge.settings.Definition require(String key) {
        dev.forge.settings.Definition definition = definitions.get(key);
        if (definition == null) {
            throw ForgeException.notFound("Unknown setting: " + key).with("settingKey", key);
        }
        return definition;
    }

    private Map<String, Object> layer(dev.forge.state.Scope scope, String owner) {
        return owner == null ? Map.of() : readDocument(scope, owner);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readDocument(dev.forge.state.Scope scope, String owner) {
        Object document = state.get(scope, owner, DOCUMENT_KEY).orElse(null);
        return document instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static String requireOwner(String owner, String what) {
        return Optional.ofNullable(owner)
                .orElseThrow(() -> ForgeException.invalidArgument("No " + what + " in context"));
    }
}
