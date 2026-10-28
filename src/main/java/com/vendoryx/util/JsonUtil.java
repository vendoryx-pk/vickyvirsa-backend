package com.vendoryx.util;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

public final class JsonUtil {

    private static final Gson GSON = new Gson();

    private JsonUtil() {}

    public static Gson gson() {
        return GSON;
    }

    public static String readBody(HttpServletRequest request) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = request.getReader()) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    public static JsonObject parseObject(String body) throws JsonSyntaxException {
        if (body == null || body.isBlank()) {
            return null;
        }
        return GSON.fromJson(body, JsonObject.class);
    }

    public static String text(JsonObject json, String key) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return null;
        }
        String value = json.get(key).getAsString();
        return value != null ? value.trim() : null;
    }

    /** First non-blank value among keys (e.g. password / newPassword). */
    public static String textAny(JsonObject json, String... keys) {
        if (json == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            String value = text(json, key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    public static Integer integer(JsonObject json, String key) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return null;
        }
        try {
            if (json.get(key).isJsonPrimitive() && json.get(key).getAsJsonPrimitive().isString()) {
                String raw = json.get(key).getAsString().trim();
                if (raw.isEmpty()) {
                    return null;
                }
                return Integer.parseInt(raw);
            }
            return json.get(key).getAsInt();
        } catch (NumberFormatException | ClassCastException e) {
            return null;
        }
    }

    public static Double decimal(JsonObject json, String key) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return null;
        }
        try {
            if (json.get(key).isJsonPrimitive() && json.get(key).getAsJsonPrimitive().isString()) {
                String raw = json.get(key).getAsString().trim();
                if (raw.isEmpty()) {
                    return null;
                }
                return Double.parseDouble(raw);
            }
            return json.get(key).getAsDouble();
        } catch (NumberFormatException | ClassCastException e) {
            return null;
        }
    }

    public static Integer queryInt(HttpServletRequest request, String name) {
        String raw = request.getParameter(name);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static String queryText(HttpServletRequest request, String name) {
        String raw = request.getParameter(name);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim();
    }

    public static Boolean bool(JsonObject json, String key) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return null;
        }
        try {
            return json.get(key).getAsBoolean();
        } catch (ClassCastException e) {
            String raw = text(json, key);
            if (raw == null) {
                return null;
            }
            return Boolean.parseBoolean(raw);
        }
    }

    public static BigDecimal money(JsonObject json, String key) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return null;
        }
        try {
            if (json.get(key).isJsonPrimitive() && json.get(key).getAsJsonPrimitive().isString()) {
                String raw = json.get(key).getAsString().trim();
                if (raw.isEmpty()) {
                    return null;
                }
                return new BigDecimal(raw);
            }
            return json.get(key).getAsBigDecimal();
        } catch (NumberFormatException | ClassCastException e) {
            return null;
        }
    }

    public static int pageOrDefault(HttpServletRequest request, int defaultValue) {
        Integer page = queryInt(request, "page");
        if (page == null || page < 1) {
            return defaultValue;
        }
        return page;
    }

    public static int sizeOrDefault(HttpServletRequest request, int defaultValue, int max) {
        Integer size = queryInt(request, "size");
        if (size == null || size < 1) {
            return defaultValue;
        }
        return Math.min(size, max);
    }

    public static void writeJson(HttpServletResponse response, int status, Object payload) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        PrintWriter out = response.getWriter();
        out.print(GSON.toJson(payload));
        out.flush();
    }

    public static void writeSuccess(HttpServletResponse response, Object data) throws IOException {
        writeSuccess(response, "Updated successfully", data);
    }

    public static void writeSuccess(HttpServletResponse response, String message, Object data) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "success");
        body.put("success", true);
        body.put("message", message);
        body.put("data", data);
        writeJson(response, HttpServletResponse.SC_OK, body);
    }

    public static void writeError(HttpServletResponse response, int status, String message) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "not_success");
        body.put("success", false);
        body.put("message", message);
        writeJson(response, status, body);
    }
}
