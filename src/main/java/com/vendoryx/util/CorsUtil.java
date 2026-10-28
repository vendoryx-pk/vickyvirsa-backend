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
 * Never uses wildcard {@code *}.
 *
 * <p>Origins come from {@code CORS_ALLOWED_ORIGINS} (alias {@code VENDORYX_CORS_ORIGIN}),
 * always merged with built-in defaults (local Angular + Cloudflare Pages).
 */
public final class CorsUtil {

    private static final String PAGES_PROD = "https://vickyvirsa-frontend.pages.dev";
    private static final String PAGES_SUFFIX = ".vickyvirsa-frontend.pages.dev";

    private static final List<String> ALLOWED = parseAllowedOrigins();

    private CorsUtil() {}

    public static void apply(HttpServletRequest request, HttpServletResponse response) {
        String origin = request.getHeader("Origin");
        if (origin == null || origin.isBlank()) {
            return;
        }
        String normalized = normalizeOrigin(origin);
        if (normalized.isEmpty() || "*".equals(normalized) || !isAllowed(normalized)) {
            return;
        }

        response.setHeader("Access-Control-Allow-Origin", origin.trim());
        response.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, DELETE, OPTIONS");
        response.setHeader("Access-Control-Allow-Headers", "Authorization, Content-Type");
        response.setHeader("Access-Control-Max-Age", "3600");
        response.setHeader("Vary", "Origin");
    }

    static boolean isAllowed(String normalizedOrigin) {
        if (ALLOWED.contains(normalizedOrigin)) {
            return true;
        }
        // Cloudflare Pages preview deployments: https://<hash>.vickyvirsa-frontend.pages.dev
        return normalizedOrigin.startsWith("https://")
                && normalizedOrigin.endsWith(PAGES_SUFFIX)
                && normalizedOrigin.length() > ("https://".length() + PAGES_SUFFIX.length());
    }

    private static List<String> parseAllowedOrigins() {
        Set<String> set = new LinkedHashSet<>();
        // Built-in defaults — always present even if env forgets Pages
        set.add("http://localhost:4200");
        set.add(PAGES_PROD);

        String raw = Env.corsAllowedOriginsRaw();
        if (raw != null && !raw.isBlank() && !"*".equals(raw.trim())) {
            for (String part : raw.split(",")) {
                String origin = normalizeOrigin(part);
                if (!origin.isEmpty() && !"*".equals(origin)) {
                    set.add(origin);
                }
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
