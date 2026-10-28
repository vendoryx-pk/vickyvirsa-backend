package com.vendoryx.config;

/**
 * Resolves env vars with Render-friendly aliases and optional local {@code .env}.
 * Prefer OS env; fall back to DotEnv file values.
 */
public final class Env {

    private Env() {}

    public static String get(String key) {
        DotEnv.load();
        return DotEnv.get(key);
    }

    public static String first(String... keys) {
        for (String key : keys) {
            String value = get(key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    public static String requireFirst(String... keys) {
        String value = first(keys);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Missing required environment variable (tried: " + String.join(", ", keys) + ")");
        }
        return value;
    }

    /** JDBC URL; appends sslmode=require when absent (Neon). */
    public static String dbUrl() {
        String url = requireFirst("DB_URL", "VENDORYX_DB_URL");
        if (!url.toLowerCase().contains("sslmode=")) {
            url = url + (url.contains("?") ? "&" : "?") + "sslmode=require";
        }
        return url;
    }

    public static String dbUser() {
        return requireFirst("DB_USER", "VENDORYX_DB_USER");
    }

    public static String dbPassword() {
        return requireFirst("DB_PASSWORD", "VENDORYX_DB_PASSWORD");
    }

    public static String corsAllowedOriginsRaw() {
        return first("CORS_ALLOWED_ORIGINS", "VENDORYX_CORS_ORIGIN");
    }
}
