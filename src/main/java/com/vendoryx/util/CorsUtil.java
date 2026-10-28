package com.vendoryx.util;

import com.vendoryx.config.Env;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reflects an allowed request Origin into Access-Control-Allow-Origin.
 * Never uses wildcard {@code *}. Origins from {@code CORS_ALLOWED_ORIGINS}
 * (alias {@code VENDORYX_CORS_ORIGIN}); default {@code http://localhost:4200}.
 */
public final class CorsUtil {

    private static final List<String> ALLOWED = parseAllowedOrigins();

    private CorsUtil() {}

    public static void apply(HttpServletRequest request, HttpServletResponse response) {
        String origin = request.getHeader("Origin");
        if (origin == null || origin.isBlank()) {
            return;
        }
        String normalized = normalizeOrigin(origin);
        if (normalized.isEmpty() || "*".equals(normalized) || !ALLOWED.contains(normalized)) {
            return;
        }

        response.setHeader("Access-Control-Allow-Origin", origin.trim());
        // Guest: GET/POST/OPTIONS; keep PUT/PATCH/DELETE for existing admin APIs
        response.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, DELETE, OPTIONS");
        response.setHeader("Access-Control-Allow-Headers", "Authorization, Content-Type");
        response.setHeader("Access-Control-Max-Age", "3600");
        response.setHeader("Vary", "Origin");
    }

    private static List<String> parseAllowedOrigins() {
        String raw = Env.corsAllowedOriginsRaw();
        Set<String> set = new LinkedHashSet<>();
        if (raw == null || raw.isBlank() || "*".equals(raw.trim())) {
            set.add("http://localhost:4200");
        } else {
            for (String part : raw.split(",")) {
                String origin = normalizeOrigin(part);
                if (!origin.isEmpty() && !"*".equals(origin)) {
                    set.add(origin);
                }
            }
            if (set.isEmpty()) {
                set.add("http://localhost:4200");
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(set));
    }

    private static String normalizeOrigin(String origin) {
        if (origin == null) {
            return "";
        }
        String t = origin.trim();
        while (t.endsWith("/")) {
            t = t.substring(0, t.length() - 1).trim();
        }
        return t;
    }
}
