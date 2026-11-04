package org.jwcarman.noir.json;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/** Shared Jackson configuration. */
public final class Json {
    private static final ObjectMapper MAPPER = base().build();
    private static final ObjectMapper CANONICAL = base()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .disable(SerializationFeature.INDENT_OUTPUT)
            .build();

    private Json() {}

    private static JsonMapper.Builder base() {
        return JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /** General-purpose mapper: ISO timestamps, unknown properties rejected. */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /** Canonical mapper: sorted keys, compact output. Used for hashing. */
    public static ObjectMapper canonical() {
        return CANONICAL;
    }
}
