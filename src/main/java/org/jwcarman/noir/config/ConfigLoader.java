package org.jwcarman.noir.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/** Loads {@link NoirConfig}: defaults overlaid with whatever the YAML file specifies. */
public final class ConfigLoader {
    private static final ObjectMapper YAML = YAMLMapper.builder()
            .addModule(new JavaTimeModule())
            .addModule(new SimpleModule().addDeserializer(Duration.class, new DurationDeserializer()))
            .disable(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    private ConfigLoader() {}

    public static NoirConfig load(Path configYaml) {
        if (!Files.exists(configYaml)) {
            return NoirConfig.defaults();
        }
        try {
            ObjectNode merged = YAML.valueToTree(NoirConfig.defaults());
            JsonNode file = YAML.readTree(configYaml.toFile());
            if (file != null && !file.isMissingNode() && !file.isNull()) {
                if (!file.isObject()) {
                    throw new IllegalArgumentException("Invalid config " + configYaml + ": top level must be a mapping");
                }
                merge(merged, (ObjectNode) file);
            }
            return YAML.treeToValue(merged, NoirConfig.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid config " + configYaml + ": " + e.getMessage(), e);
        }
    }

    private static void merge(ObjectNode target, ObjectNode overlay) {
        overlay.fields().forEachRemaining(entry -> {
            var existing = target.get(entry.getKey());
            if (existing instanceof ObjectNode existingObject && entry.getValue() instanceof ObjectNode overlayObject) {
                merge(existingObject, overlayObject);
            } else {
                target.set(entry.getKey(), entry.getValue());
            }
        });
    }

    /** Accepts ISO-8601 ({@code PT60M}) or shorthand ({@code 45s}, {@code 60m}, {@code 2h}). */
    static final class DurationDeserializer extends StdDeserializer<Duration> {
        private static final Pattern SHORTHAND = Pattern.compile("(\\d+)([smh])");

        DurationDeserializer() {
            super(Duration.class);
        }

        @Override
        public Duration deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            var text = p.getValueAsString();
            if (text == null) {
                return (Duration) ctxt.handleUnexpectedToken(Duration.class, p);
            }
            text = text.trim();
            var m = SHORTHAND.matcher(text);
            if (m.matches()) {
                long n = Long.parseLong(m.group(1));
                return switch (m.group(2)) {
                    case "s" -> Duration.ofSeconds(n);
                    case "m" -> Duration.ofMinutes(n);
                    default -> Duration.ofHours(n);
                };
            }
            try {
                return Duration.parse(text);
            } catch (DateTimeParseException e) {
                return (Duration) ctxt.handleWeirdStringValue(Duration.class, text,
                        "expected ISO-8601 duration or <n>s|m|h");
            }
        }
    }
}
