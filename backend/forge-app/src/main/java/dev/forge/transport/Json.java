package dev.forge.transport;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.forge.core.ForgeException;
import dev.forge.core.Ids;
import dev.forge.core.command.CommandId;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * JSON encoding, confined to the transport.
 *
 * <p>Jackson lives here and nowhere else. No feature, service or domain type carries a
 * serialisation annotation, so the wire format can change — or a second transport can use a
 * different one — without touching application code.
 *
 * <p>Identifier value types are written as plain strings rather than as
 * {@code {"value": "..."}}, so the wire stays readable and a client never has to know that
 * {@code WorkspaceId} is a record on the server.
 */
public final class Json {

    private final ObjectMapper mapper;

    public Json() {
        SimpleModule identifiers = new SimpleModule("forge-identifiers");
        identifiers.addSerializer(Ids.Id.class, new JsonSerializer<>() {
            @Override
            public void serialize(Ids.Id id, JsonGenerator generator, SerializerProvider provider)
                    throws IOException {
                generator.writeString(id.value());
            }
        });
        identifiers.addSerializer(CommandId.class, new JsonSerializer<>() {
            @Override
            public void serialize(CommandId id, JsonGenerator generator, SerializerProvider provider)
                    throws IOException {
                generator.writeString(id.value());
            }
        });

        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .registerModule(identifiers)
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw ForgeException.internal("Could not encode response", e);
        }
    }

    /**
     * Reads a request body into a plain map. Requests are never bound to typed classes here:
     * arguments reach application code as {@code Args}, which validates them where they are used.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> readObject(InputStream input) {
        try {
            Map<String, Object> value = mapper.readValue(input, Map.class);
            return value == null ? Map.of() : value;
        } catch (IOException e) {
            throw ForgeException.invalidArgument("Request body is not valid JSON");
        }
    }
}
