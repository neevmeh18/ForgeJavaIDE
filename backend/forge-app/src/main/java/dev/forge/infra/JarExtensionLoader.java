package dev.forge.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.forge.core.ForgeException;
import dev.forge.core.Ids.ExtensionId;
import dev.forge.core.Log;
import dev.forge.core.extension.Extension;
import dev.forge.core.extension.ExtensionDescriptor;
import dev.forge.core.extension.ExtensionRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Finds extensions as jars in {@code IDE_EXTENSIONS_DIR} and loads them on demand.
 *
 * <p>Discovery reads each jar's {@code forge-extension.json} without loading a single class:
 * activation conditions are known up front, so an extension that is never needed is never
 * loaded. Each one gets its own {@link URLClassLoader}, which keeps extensions from colliding
 * over shared class names and lets a deactivated extension be released.
 *
 * <p>A jar with a broken or missing manifest is skipped with a warning. Nothing about a faulty
 * extension is allowed to stop the IDE from starting.
 */
public final class JarExtensionLoader {

    private static final Log log = Log.of(JarExtensionLoader.class);
    private static final String MANIFEST = "forge-extension.json";

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path directory;

    public JarExtensionLoader(Path directory) {
        this.directory = directory;
    }

    /**
     * Registers every well-formed extension found. Activation happens later, on demand.
     *
     * <p>{@code onContributions} receives each manifest's declarative contributions at discovery
     * time — before any class is loaded — so a setting an extension declares is visible in the
     * settings UI even if the extension never activates.
     */
    public void discoverInto(ExtensionRegistry registry,
                             java.util.function.BiConsumer<ExtensionId, Map<String, Object>> onContributions) {
        if (!Files.isDirectory(directory)) {
            log.with("directory", directory).debug("No extensions directory; skipping discovery");
            return;
        }
        try (DirectoryStream<Path> jars = Files.newDirectoryStream(directory, "*.jar")) {
            for (Path jar : jars) {
                try {
                    ExtensionDescriptor descriptor = readManifest(jar);
                    registry.discovered(descriptor, manifest -> instantiate(jar, manifest));
                    if (!descriptor.contributes().isEmpty()) {
                        onContributions.accept(descriptor.id(), descriptor.contributes());
                    }
                } catch (RuntimeException e) {
                    log.with("jar", jar.getFileName()).warn("Skipping unusable extension jar: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Could not scan the extensions directory", e);
        }
    }

    @SuppressWarnings("unchecked")
    private ExtensionDescriptor readManifest(Path jar) {
        try (java.util.jar.JarFile file = new java.util.jar.JarFile(jar.toFile())) {
            var entry = file.getJarEntry(MANIFEST);
            if (entry == null) {
                throw ForgeException.invalidArgument("No " + MANIFEST + " in the jar");
            }
            try (InputStream input = file.getInputStream(entry)) {
                Map<String, Object> fields = mapper.readValue(input, Map.class);
                List<String> activation = new ArrayList<>();
                if (fields.get("activationEvents") instanceof List<?> declared) {
                    declared.forEach(event -> activation.add(String.valueOf(event)));
                }
                return new ExtensionDescriptor(
                        ExtensionId.of(require(fields, "id")),
                        String.valueOf(fields.getOrDefault("name", "")),
                        String.valueOf(fields.getOrDefault("version", "0.0.0")),
                        require(fields, "main"),
                        String.valueOf(fields.getOrDefault("description", "")),
                        List.copyOf(activation),
                        fields.get("contributes") instanceof Map<?, ?> contributes
                                ? (Map<String, Object>) contributes
                                : Map.of());
            }
        } catch (IOException e) {
            throw ForgeException.invalidArgument("Could not read the extension manifest");
        }
    }

    private Extension instantiate(Path jar, ExtensionDescriptor descriptor) throws Exception {
        URLClassLoader loader = new URLClassLoader(descriptor.id().value(),
                new URL[] {jar.toUri().toURL()}, getClass().getClassLoader());
        Class<?> type = Class.forName(descriptor.mainClass(), true, loader);
        if (!Extension.class.isAssignableFrom(type)) {
            loader.close();
            throw ForgeException.invalidArgument(descriptor.mainClass() + " does not implement Extension");
        }
        log.with("extensionId", descriptor.id()).debug("Extension class loaded");
        return (Extension) type.getDeclaredConstructor().newInstance();
    }

    private static String require(Map<String, Object> fields, String key) {
        Object value = fields.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw ForgeException.invalidArgument("Extension manifest is missing '" + key + "'");
        }
        return text;
    }
}
