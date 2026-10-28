package com.vendoryx.util;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

public final class RoleGuard {

    public static final String ATTR_ADMIN_ID = "adminId";
    public static final String ATTR_ROLE = "role";

    /** @deprecated B2B remnant — prefer {@link #ATTR_ADMIN_ID} */
    @Deprecated
    public static final String ATTR_USER_ID = "adminId";
    /** @deprecated B2B remnant */
    @Deprecated
    public static final String ATTR_SHOP_IDS = "shopIds";

    /** admin_users.role CHECK: OWNER, STAFF */
    public static final String[] ADMIN_ROLES = {"OWNER", "STAFF"};

    private RoleGuard() {}

    public static boolean isAdminRole(String role) {
        if (role == null) {
            return false;
        }
        for (String allowed : ADMIN_ROLES) {
            if (allowed.equalsIgnoreCase(role)) {
                return true;
            }
        }
        return false;
    }

    public static boolean requireAdmin(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        return requireRole(req, resp, ADMIN_ROLES);
    }

    /**
     * @return true if role is allowed; otherwise writes 403 and returns false
     */
    public static boolean requireRole(HttpServletRequest req, HttpServletResponse resp, String... allowedRoles)
            throws IOException {
        Object roleObj = req.getAttribute(ATTR_ROLE);
        String role = roleObj != null ? roleObj.toString() : null;

        if (role != null && allowedRoles != null) {
            for (String allowed : allowedRoles) {
                if (allowed != null && allowed.equalsIgnoreCase(role)) {
                    return true;
                }
            }
        }

        JsonUtil.writeError(resp, HttpServletResponse.SC_FORBIDDEN, "Forbidden");
        return false;
    }

    public static Integer getAdminId(HttpServletRequest req) {
        Object value = req.getAttribute(ATTR_ADMIN_ID);
        if (value instanceof Integer) {
            return (Integer) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return null;
    }

    /** @deprecated use {@link #getAdminId(HttpServletRequest)} */
    @Deprecated
    public static Integer getUserId(HttpServletRequest req) {
        return getAdminId(req);
    }

    /** @deprecated B2B remnant */
    @Deprecated
    @SuppressWarnings("unchecked")
    public static java.util.List<Integer> getShopIds(HttpServletRequest req) {
        Object value = req.getAttribute(ATTR_SHOP_IDS);
        if (value instanceof java.util.List<?> list) {
            return (java.util.List<Integer>) list;
        }
        return java.util.Collections.emptyList();
    }

    /** @deprecated B2B remnant */
    @Deprecated
    public static boolean ownsShop(HttpServletRequest req, int shopId) {
        return getShopIds(req).contains(shopId);
    }
}
