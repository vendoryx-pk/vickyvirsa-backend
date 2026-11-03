package com.vendoryx.dao;

import com.vendoryx.config.DBConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

/**
 * Persist rows in email_log (status: PENDING | SENT | FAILED;
 * email_type includes ORDER_ALERT_OWNER).
 */
public class EmailLogDAO {

    public static final String TYPE_ORDER_ALERT_OWNER = "ORDER_ALERT_OWNER";
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_SENT = "SENT";
    public static final String STATUS_FAILED = "FAILED";

    public long insertPending(long orderId, String emailType, String toEmail, String subject)
            throws SQLException {
        String sql = """
                INSERT INTO email_log (order_id, email_type, to_email, subject, status, attempts)
                VALUES (?, ?, ?, ?, 'PENDING', 0)
                RETURNING email_id
                """;
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, orderId);
            ps.setString(2, emailType);
            ps.setString(3, toEmail);
            ps.setString(4, subject);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("Failed to insert email_log");
                }
                return rs.getLong(1);
            }
        }
    }

    public void markSent(long emailId, String providerMessageId) throws SQLException {
        String sql = """
                UPDATE email_log
                SET status = 'SENT',
                    attempts = attempts + 1,
                    provider_message_id = ?,
                    error_message = NULL,
                    sent_at = now(),
                    next_retry_at = NULL
                WHERE email_id = ?
                """;
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            if (providerMessageId == null || providerMessageId.isBlank()) {
                ps.setNull(1, Types.VARCHAR);
            } else {
                ps.setString(1, providerMessageId);
            }
            ps.setLong(2, emailId);
            ps.executeUpdate();
        }
    }

    public void markFailed(long emailId, String errorMessage) throws SQLException {
        String sql = """
                UPDATE email_log
                SET status = 'FAILED',
                    attempts = attempts + 1,
                    error_message = ?,
                    sent_at = NULL
                WHERE email_id = ?
                """;
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            String err = errorMessage;
            if (err != null && err.length() > 2000) {
                err = err.substring(0, 2000);
            }
            ps.setString(1, err);
            ps.setLong(2, emailId);
            ps.executeUpdate();
        }
    }
}
