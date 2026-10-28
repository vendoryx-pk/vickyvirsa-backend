package com.vendoryx.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Loads optional project-root {@code .env} for local runs (IDE / java -jar).
 * Real OS environment variables always win over .env values.
 */
public final class DotEnv {

    private static final Map<String, String> FILE_VARS = new HashMap<>();
    private static boolean loaded;

    private DotEnv() {}

    public static synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        Path path = Path.of(".env");
        if (!Files.isRegularFile(path)) {
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();
                if ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }
                FILE_VARS.put(key, value);
            }
            System.out.println("Loaded environment from .env (" + FILE_VARS.size() + " keys)");
        } catch (IOException e) {
            System.err.println("WARNING: could not read .env: " + e.getMessage());
        }
    }

    public static String get(String key) {
        String fromOs = System.getenv(key);
        if (fromOs != null && !fromOs.isBlank()) {
            return fromOs.trim();
        }
        String fromFile = FILE_VARS.get(key);
        if (fromFile != null && !fromFile.isBlank()) {
            return fromFile.trim();
        }
        return null;
    }
}
