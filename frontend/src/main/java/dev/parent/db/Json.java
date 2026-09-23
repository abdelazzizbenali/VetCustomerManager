package dev.parent.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/** Shared Jackson mapper + tiny helpers to read Postgres/PostgREST values safely. */
public final class Json {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
    }

    public static ObjectNode obj() {
        return MAPPER.createObjectNode();
    }

    public static JsonNode parse(byte[] bytes) throws IOException {
        return MAPPER.readTree(bytes);
    }

    public static String text(JsonNode n, String field, String def) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? def : v.asText(def);
    }

    public static String text(JsonNode n, String field) {
        return text(n, field, "");
    }

    public static double dbl(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) {
            return 0;
        }
        if (v.isNumber()) {
            return v.asDouble();
        }
        try {
            return Double.parseDouble(v.asText("0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static int integer(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) {
            return 0;
        }
        if (v.isNumber()) {
            return v.asInt();
        }
        try {
            return (int) Double.parseDouble(v.asText("0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static long lng(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? 0 : v.asLong(0);
    }

    public static boolean bool(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v != null && !v.isNull() && v.asBoolean(false);
    }

    /** Parses "2026-09-21T10:15:30.12+00:00" or a plain Instant into {@link Instant}. */
    public static Instant instant(JsonNode n, String field) {
        return parseInstant(text(n, field, null));
    }

    public static Instant parseInstant(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return OffsetDateTime.parse(raw).toInstant();
        } catch (DateTimeParseException ignored) {
        }
        try {
            // Postgres sometimes emits "2026-09-21 10:15:30+00"
            return OffsetDateTime.parse(raw, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSSSSS][.SSS]X")).toInstant();
        } catch (DateTimeParseException ignored) {
        }
        return null;
    }

    public static LocalDate date(JsonNode n, String field) {
        String raw = text(n, field, null);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.substring(0, Math.min(10, raw.length())));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    public static void putNullableText(ObjectNode o, String field, String value) {
        if (value == null) {
            o.putNull(field);
        } else {
            o.put(field, value);
        }
    }
}
