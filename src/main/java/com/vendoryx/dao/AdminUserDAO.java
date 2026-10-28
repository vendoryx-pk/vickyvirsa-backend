package com.vendoryx.dao;

import com.vendoryx.config.DBConnection;
import com.vendoryx.model.AdminUser;
import com.vendoryx.util.PasswordUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * VickyVirsa admin_users auth (roles: OWNER, STAFF).
 */
public class AdminUserDAO {

    public AdminUser authenticate(String usernameOrEmail, String password) throws SQLException {
        String sql = """
                SELECT admin_id, username, email, password_hash, full_name, role, is_active
                FROM admin_users
                WHERE is_active = TRUE
                  AND (LOWER(username) = LOWER(?) OR LOWER(email) = LOWER(?))
                LIMIT 1
                """;
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, usernameOrEmail);
            ps.setString(2, usernameOrEmail);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                String hash = rs.getString("password_hash");
                if (!PasswordUtil.verify(password, hash)) {
                    return null;
                }
                AdminUser admin = map(rs);
                if (PasswordUtil.isLegacyPlainText(hash)) {
                    updatePasswordHash(admin.getAdminId(), PasswordUtil.hash(password));
                }
                return admin;
            }
        }
    }

    public void updateLastLogin(int adminId) throws SQLException {
        String sql = "UPDATE admin_users SET last_login_at = now(), updated_at = now() WHERE admin_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, adminId);
            ps.executeUpdate();
        }
    }

    public void updatePasswordHash(int adminId, String passwordHash) throws SQLException {
        String sql = "UPDATE admin_users SET password_hash = ?, updated_at = now() WHERE admin_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, passwordHash);
            ps.setInt(2, adminId);
            ps.executeUpdate();
        }
    }

    private AdminUser map(ResultSet rs) throws SQLException {
        AdminUser a = new AdminUser();
        a.setAdminId(rs.getInt("admin_id"));
        a.setUsername(rs.getString("username"));
        a.setEmail(rs.getString("email"));
        a.setFullName(rs.getString("full_name"));
        a.setRole(rs.getString("role"));
        a.setActive(rs.getBoolean("is_active"));
        return a;
    }
}
