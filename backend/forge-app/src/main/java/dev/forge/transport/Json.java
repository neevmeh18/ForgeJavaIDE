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












public final class Json {

    private final ObjectMapper mapper;

    public Json() {
        SimpleModule identifiers = new SimpleModule("forge-identifiers");
        identifiers.addSerializer(dev.forge.core.Id.class, new JsonSerializer<>() {
            @Override
            public void serialize(dev.forge.core.Id id, JsonGenerator generator, SerializerProvider provider)
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
