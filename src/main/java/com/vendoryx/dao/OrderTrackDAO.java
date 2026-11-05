package com.vendoryx.dao;

import com.vendoryx.config.DBConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Public order tracking lookup (order_number + phone). No address / admin notes.
 */
public class OrderTrackDAO {

    /**
     * @return public track payload or null if no match
     */
    public Map<String, Object> findPublicTrack(String orderNumber, String phoneE164) throws SQLException {
        if (orderNumber == null || orderNumber.isBlank() || phoneE164 == null || phoneE164.isBlank()) {
            return null;
        }
        String sql = """
                SELECT o.order_id, o.order_number, o.order_status, o.payment_status,
                       o.created_at, o.total_amount, o.ship_city
                FROM orders o
                WHERE LOWER(o.order_number) = LOWER(?)
                  AND (o.customer_phone = ? OR o.ship_phone = ?)
                LIMIT 1
                """;
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, orderNumber.trim());
            ps.setString(2, phoneE164);
            ps.setString(3, phoneE164);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                long orderId = rs.getLong("order_id");
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("orderNumber", rs.getString("order_number"));
                out.put("status", rs.getString("order_status"));
                out.put("paymentStatus", rs.getString("payment_status"));
                out.put("createdAt", ts(rs.getTimestamp("created_at")));
                out.put("total", rs.getBigDecimal("total_amount"));
                out.put("shippingCity", rs.getString("ship_city"));
                out.put("items", loadItems(conn, orderId));
                out.put("statusHistory", loadHistory(conn, orderId));
                return out;
            }
        }
    }

    private List<Map<String, Object>> loadItems(Connection conn, long orderId) throws SQLException {
        String sql = """
                SELECT product_name, variant_name, quantity, unit_price
                FROM order_items WHERE order_id = ? ORDER BY order_item_id
                """;
        List<Map<String, Object>> items = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", rs.getString("product_name"));
                    m.put("variant", rs.getString("variant_name"));
                    m.put("qty", rs.getInt("quantity"));
                    m.put("price", rs.getBigDecimal("unit_price"));
                    items.add(m);
                }
            }
        }
        return items;
    }

    private List<Map<String, Object>> loadHistory(Connection conn, long orderId) throws SQLException {
        String sql = """
                SELECT new_status, customer_note, changed_at
                FROM order_status_history
                WHERE order_id = ?
                ORDER BY changed_at ASC, history_id ASC
                """;
        List<Map<String, Object>> history = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("status", rs.getString("new_status"));
                    m.put("time", ts(rs.getTimestamp("changed_at")));
                    // Never expose comments/internalNote on public track
                    m.put("customerNote", rs.getString("customer_note"));
                    history.add(m);
                }
            }
        }
        return history;
    }

    private static String ts(Timestamp t) {
        return t == null ? null : t.toInstant().toString();
    }
}
