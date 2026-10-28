package com.vendoryx.util;

import org.mindrot.jbcrypt.BCrypt;

public final class PasswordUtil {

    private PasswordUtil() {}

    public static String hash(String plain) {
        if (plain == null) {
            throw new IllegalArgumentException("Password cannot be null");
        }
        return BCrypt.hashpw(plain, BCrypt.gensalt());
    }

    /**
     * Verifies plain password against stored value.
     * Legacy: if stored does not start with "$2", treat as plain text.
     */
    public static boolean verify(String plain, String stored) {
        if (plain == null || stored == null || stored.isBlank()) {
            return false;
        }
        String value = stored.trim();
        if (isBcrypt(value)) {
            return BCrypt.checkpw(plain, value);
        }
        return value.equals(plain);
    }

    /**
     * True only for legacy plain-text stored passwords (not starting with "$2").
     * BCrypt hashes never need rehash here — including older cost factors.
     */
    public static boolean needsRehash(String stored) {
        return isLegacyPlainText(stored);
    }

    /** Stored value is legacy plain text (must be migrated once to BCrypt). */
    public static boolean isLegacyPlainText(String stored) {
        if (stored == null || stored.isBlank()) {
            return false;
        }
        return !isBcrypt(stored.trim());
    }

    /** BCrypt hashes start with "$2" ($2a$ / $2b$ / $2y$). */
    public static boolean isBcrypt(String stored) {
        return stored != null && stored.trim().startsWith("$2");
    }
}
