package com.vendoryx.dao;

import com.vendoryx.config.DBConnection;
import com.vendoryx.util.JsonUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Map;

/** Writes rows to admin_audit_log. */
public class AdminAuditDAO {

    public void log(Integer adminId, String action, String entityType, String entityId, Object details)
            throws SQLException {
        String sql = """
                INSERT INTO admin_audit_log (admin_id, action, entity_type, entity_id, details)
                VALUES (?, ?, ?, ?, ?::jsonb)
                """;
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            if (adminId == null) {
                ps.setNull(1, Types.INTEGER);
            } else {
                ps.setInt(1, adminId);
            }
            ps.setString(2, action);
            ps.setString(3, entityType);
            ps.setString(4, entityId);
            if (details == null) {
                ps.setNull(5, Types.OTHER);
            } else if (details instanceof String s) {
                ps.setString(5, s);
            } else {
                ps.setString(5, JsonUtil.gson().toJson(details));
            }
            ps.executeUpdate();
        }
    }

    public void log(Integer adminId, String action, String entityType, long entityId, Map<String, Object> details)
            throws SQLException {
        log(adminId, action, entityType, String.valueOf(entityId), details);
    }
}