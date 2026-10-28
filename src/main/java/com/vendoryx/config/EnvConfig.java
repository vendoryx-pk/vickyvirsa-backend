package com.vendoryx.config;

/**
 * Validates required environment variables at process start (before Tomcat).
 */
public final class EnvConfig {

    private EnvConfig() {}

    public static void validateRequired() {
        DotEnv.load();
        Env.dbUrl();
        Env.dbUser();
        Env.dbPassword();

        String jwt = Env.first("VENDORYX_JWT_SECRET", "JWT_SECRET");
        if (jwt == null || jwt.isBlank()) {
            fail("Missing required environment variable: VENDORYX_JWT_SECRET (min 32 characters).");
        }
        if (jwt.trim().length() < 32) {
            fail("VENDORYX_JWT_SECRET must be at least 32 characters (got " + jwt.trim().length() + ").");
        }
    }

    public static int port() {
        String raw = Env.first("PORT");
        if (raw == null || raw.isBlank()) {
            return 8080;
        }
        try {
            int port = Integer.parseInt(raw.trim());
            if (port < 1 || port > 65535) {
                fail("PORT must be between 1 and 65535 (got " + port + ").");
            }
            return port;
        } catch (NumberFormatException e) {
            fail("PORT must be a number (got '" + raw + "').");
            return 8080;
        }
    }

    private static void fail(String message) {
        System.err.println("FATAL: " + message);
        System.err.println("Required: DB_URL (or VENDORYX_DB_URL), DB_USER, DB_PASSWORD,");
        System.err.println("          VENDORYX_JWT_SECRET (>=32 chars)");
        System.err.println("Optional: CORS_ALLOWED_ORIGINS (or VENDORYX_CORS_ORIGIN), PORT (default 8080)");
        throw new IllegalStateException(message);
    }
}
