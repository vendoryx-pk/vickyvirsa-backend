package com.vendoryx.util;

/**
 * Pakistan phone normalization used by guest checkout and order tracking.
 * DB expects E.164 {@code ^\\+[0-9]{10,15}$}.
 */
public final class PhoneUtil {

    private PhoneUtil() {}

    /**
     * Accepts 03XXXXXXXXX / 3XXXXXXXXX / 92... / +92...
     * @return E.164 or null if invalid
     */
    public static String normalizePhoneE164(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        String p = phone.replaceAll("[\\s-]", "");
        if (p.matches("^\\+[0-9]{10,15}$")) {
            return p;
        }
        if (p.matches("^03[0-9]{9}$")) {
            return "+92" + p.substring(1);
        }
        if (p.matches("^3[0-9]{9}$")) {
            return "+92" + p;
        }
        if (p.matches("^92[0-9]{10}$")) {
            return "+" + p;
        }
        return null;
    }
}
