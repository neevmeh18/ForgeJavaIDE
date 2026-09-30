package dev.forge.tasklog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.forge.core.ForgeException;
import dev.forge.core.Ids.TaskExecutionId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class TaskLogStore {
    private static final int MAX_RECORDS = 2000;

    private record Range(int start, int length) { }

    private static final class Index {
        final List<Range> visible = new ArrayList<>();
        int characters;
        int bytes;
    }

    private final Path root;
    private final String unit;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<TaskExecutionId, Index> indexes = new ConcurrentHashMap<>();

    TaskLogStore(Path root, String unit) {
        this.root = root;
        this.unit = "bytes".equalsIgnoreCase(unit) ? "bytes" : "characters";
    }

    void start() {
        try {
            Files.createDirectories(root);
            setDirectoryPermissions(root);
            clearStaleEntries();
        } catch (IOException e) {
            throw ForgeException.unavailable("Could not prepare task log storage");
        }
    }

    void create(TaskExecutionId executionId) throws IOException {
        Path directory = root.resolve(executionId.value());
        Files.createDirectories(directory);
        setDirectoryPermissions(directory);
        Path file = file(executionId);
        Files.deleteIfExists(file);
        Files.deleteIfExists(runtimeFile(executionId));
        Files.createFile(file);
        setFilePermissions(file);
        indexes.put(executionId, new Index());
    }

    void writeRuntime(TaskExecutionId executionId, Map<String, Object> record) throws IOException {
        String line;
        try {
            line = mapper.writeValueAsString(record) + "\n";
        } catch (JsonProcessingException e) {
            throw new IOException(e);
        }
        Path runtime = runtimeFile(executionId);
        Files.writeString(runtime, line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        setFilePermissions(runtime);
    }

    void remove(TaskExecutionId executionId) {
        indexes.remove(executionId);
        Path directory = root.resolve(executionId.value());
        if (!Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    int visibleCount(TaskExecutionId executionId) {
        Index index = indexes.get(executionId);
        if (index == null) return 0;
        synchronized (index) {
            return index.visible.size();
        }
    }

    void append(TaskExecutionId executionId, Map<String, Object> record, boolean visible) throws IOException {
        Index index = indexes.get(executionId);
        if (index == null) throw new IOException("Task log is not open");
        String line;
        try {
            line = mapper.writeValueAsString(record) + "\n";
        } catch (JsonProcessingException e) {
            throw new IOException(e);
        }
        byte[] encoded = line.getBytes(StandardCharsets.UTF_8);
        synchronized (index) {
            if (visible && index.visible.size() < MAX_RECORDS) {
                int start = "bytes".equals(unit) ? index.bytes : index.characters;
                int length = "bytes".equals(unit) ? encoded.length : line.length();
                index.visible.add(new Range(start, length));
            }
            Files.write(file(executionId), encoded, StandardOpenOption.APPEND);
            index.characters += line.length();
            index.bytes += encoded.length;
        }
    }

    List<Map<String, Object>> records(TaskExecutionId executionId) {
        Index index = indexes.get(executionId);
        if (index == null) throw ForgeException.notFound("Task log is not available: " + executionId);
        String content;
        try {
            content = Files.readString(file(executionId), StandardCharsets.UTF_8);
            Path runtime = runtimeFile(executionId);
            if (Files.isRegularFile(runtime)) {
                content += Files.readString(runtime, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw ForgeException.unavailable("Could not read task log");
        }

        Map<String, Map<String, Object>> unique = new LinkedHashMap<>();
        synchronized (index) {
            for (Range range : index.visible) {
                int start = "bytes".equals(unit)
                        ? byteOffsetToCharacterIndex(content, range.start())
                        : Math.clamp(range.start(), 0, content.length());
                int end = Math.clamp(start + range.length(), start, content.length());
                if ("bytes".equals(unit) && end < content.length() && end > start
                        && content.charAt(end - 1) != '\n') {
                    int newline = content.indexOf('\n', end);
                    end = newline >= 0 ? newline + 1 : content.length();
                }
                for (String line : content.substring(start, end).split("\\R")) {
                    Map<String, Object> parsed = parse(line);
                    if (parsed == null) continue;
                    try {
                        unique.putIfAbsent(mapper.writeValueAsString(parsed), parsed);
                    } catch (JsonProcessingException ignored) {
                        unique.putIfAbsent(parsed.toString(), parsed);
                    }
                }
            }
        }
        return new ArrayList<>(unique.values());
    }

    private int byteOffsetToCharacterIndex(String content, int byteOffset) {
        if (byteOffset <= 0) return 0;
        byte[] encoded = content.getBytes(StandardCharsets.UTF_8);
        int limit = Math.clamp(byteOffset, 0, encoded.length);
        return new String(encoded, 0, limit, StandardCharsets.UTF_8).length();
    }



    private void clearStaleEntries() throws IOException {
        try (var entries = Files.list(root)) {
            for (Path entry : entries.toList()) {
                deleteTree(entry);
            }
        }
        indexes.clear();
    }

    private void deleteTree(Path path) throws IOException {
        if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(path)) {
            for (Path child : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(child);
            }
        }
    }

    private void setDirectoryPermissions(Path directory) throws IOException {
        try {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException ignored) {
        }
    }

    private void setFilePermissions(Path file) throws IOException {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
        }
    }

    private Path file(TaskExecutionId executionId) {
        return root.resolve(executionId.value()).resolve("events.jsonl");
    }

    private Path runtimeFile(TaskExecutionId executionId) {
        return root.resolve(executionId.value()).resolve("runtime.jsonl");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String line) {
        if (line == null || line.isBlank()) return null;
        try {
            JsonNode node = mapper.readTree(line);
            if (!node.isObject()) return null;
            return mapper.convertValue(node, LinkedHashMap.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
