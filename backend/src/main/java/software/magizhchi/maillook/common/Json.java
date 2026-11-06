package software.magizhchi.maillook.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/** Tiny helpers for the JSON columns (address lists, attachment lists). */
public final class Json {
    private static final ObjectMapper M = new ObjectMapper();

    private Json() {}

    public static String write(Object o) {
        try {
            return M.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static List<String> strings(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return M.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    public static List<Map<String, Object>> maps(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return M.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}
