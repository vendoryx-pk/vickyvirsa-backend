package com.vendoryx.dao;

import com.vendoryx.config.DBConnection;
import com.vendoryx.model.PageAccess;
import com.vendoryx.model.User;
import com.vendoryx.util.PasswordUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class UserDAO {

    /**
     * Validates username + password against the database (is_active required).
     * Migrates legacy plain-text password_hash to BCrypt once on successful login.
     * BCrypt users: password_hash is never written on login (last_login_at only, via LoginServlet).
     *
     * @return user with role + allowed pages, or null if credentials are invalid
     */
    public User authenticate(String username, String password) throws SQLException {
        String query = """
                SELECT u.user_id, u.username, u.full_name, u.email, u.password_hash,
                       r.role_name, p.page_name, p.route_path, p.icon
                FROM users u
                JOIN roles r ON u.role_id = r.role_id
                LEFT JOIN role_pages rp ON r.role_id = rp.role_id
                LEFT JOIN pages p ON rp.page_id = p.page_id
                WHERE u.username = ? AND u.is_active = TRUE
                """;

        User user = null;
        List<PageAccess> pages = new ArrayList<>();
        String storedHash = null;

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(query)) {

            ps.setString(1, username);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (user == null) {
                        storedHash = rs.getString("password_hash");
                        if (!PasswordUtil.verify(password, storedHash)) {
                            return null;
                        }
                        user = mapUser(rs);
                    }

                    String route = rs.getString("route_path");
                    if (route != null) {
                        pages.add(new PageAccess(
                                rs.getString("page_name"),
                                route,
                                rs.getString("icon")
                        ));
                    }
                }
            }
        }

        if (user == null) {
            return null;
        }

        // Only migrate legacy plain text → BCrypt. Never rewrite existing BCrypt hashes
        // (including older cost factors) on login.
        if (PasswordUtil.isLegacyPlainText(storedHash)) {
            updatePasswordHash(user.getUserId(), PasswordUtil.hash(password));
        }

        user.setAllowedPages(pages);
        return user;
    }

    public List<Integer> getActiveShopIds(int userId) throws SQLException {
        String sql = """
                SELECT shop_id FROM shop_ownership
                WHERE user_id = ? AND is_active = TRUE
                ORDER BY shop_id
                """;
        List<Integer> shopIds = new ArrayList<>();

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    shopIds.add(rs.getInt("shop_id"));
                }
            }
        }
        return shopIds;
    }

    public void updateLastLogin(int userId) throws SQLException {
        String sql = "UPDATE users SET last_login_at = CURRENT_TIMESTAMP WHERE user_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            ps.executeUpdate();
        }
    }

    public void updatePasswordHash(int userId, String passwordHash) throws SQLException {
        String sql = "UPDATE users SET password_hash = ? WHERE user_id = ? AND is_active = TRUE";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, passwordHash);
            ps.setInt(2, userId);
            ps.executeUpdate();
        }
    }

    public String getPasswordHash(int userId) throws SQLException {
        String sql = "SELECT password_hash FROM users WHERE user_id = ? AND is_active = TRUE";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("password_hash");
                }
            }
        }
        return null;
    }

    public User findById(int userId) throws SQLException {
        String query = """
                SELECT u.user_id, u.username, u.full_name, u.email,
                       r.role_name, p.page_name, p.route_path, p.icon
                FROM users u
                JOIN roles r ON u.role_id = r.role_id
                LEFT JOIN role_pages rp ON r.role_id = rp.role_id
                LEFT JOIN pages p ON rp.page_id = p.page_id
                WHERE u.user_id = ? AND u.is_active = TRUE
                """;

        User user = null;
        List<PageAccess> pages = new ArrayList<>();

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(query)) {

            ps.setInt(1, userId);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (user == null) {
                        user = mapUser(rs);
                    }
                    String route = rs.getString("route_path");
                    if (route != null) {
                        pages.add(new PageAccess(
                                rs.getString("page_name"),
                                route,
                                rs.getString("icon")
                        ));
                    }
                }
            }
        }

        if (user != null) {
            user.setAllowedPages(pages);
        }
        return user;
    }

    /**
     * Updates profile fields. newPasswordHash must already be BCrypt-hashed when non-null.
     * When newPasswordHash is null, password_hash is omitted from the UPDATE entirely
     * (name/email-only saves never touch the password).
     */
    public User updateProfile(int userId, String fullName, String email, String newPasswordHash) throws SQLException {
        StringBuilder sql = new StringBuilder("UPDATE users SET ");
        List<Object> params = new ArrayList<>();

        if (fullName != null && !fullName.isBlank()) {
            sql.append("full_name = ?, ");
            params.add(fullName.trim());
        }
        if (email != null && !email.isBlank()) {
            sql.append("email = ?, ");
            params.add(email.trim());
        }
        // password_hash only when servlet already verified currentPassword and hashed newPassword
        if (newPasswordHash != null && !newPasswordHash.isBlank()) {
            sql.append("password_hash = ?, ");
            params.add(newPasswordHash);
        }

        if (params.isEmpty()) {
            return null;
        }

        sql.setLength(sql.length() - 2);
        sql.append(" WHERE user_id = ? AND is_active = TRUE");
        params.add(userId);

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {

            for (int i = 0; i < params.size(); i++) {
                Object value = params.get(i);
                if (value instanceof Integer) {
                    ps.setInt(i + 1, (Integer) value);
                } else {
                    ps.setString(i + 1, String.valueOf(value));
                }
            }

            if (ps.executeUpdate() == 0) {
                return null;
            }
        }

        return findById(userId);
    }

    private User mapUser(ResultSet rs) throws SQLException {
        return new User(
                rs.getInt("user_id"),
                rs.getString("username"),
                rs.getString("full_name"),
                rs.getString("email"),
                rs.getString("role_name")
        );
    }
}
