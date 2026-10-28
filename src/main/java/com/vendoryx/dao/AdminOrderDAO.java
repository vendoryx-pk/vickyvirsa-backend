package com.vendoryx.dao;

import com.vendoryx.config.DBConnection;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Admin order list/detail for VickyVirsa B2C schema. */
public class AdminOrderDAO {

    public Map<String, Object> list(String status, LocalDate from, LocalDate to, String q,
                                    int page, int size) throws SQLException {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (status != null && !status.isBlank()) {
            where.append(" AND o.order_status = ?");
            params.add(status.trim().toUpperCase());
        }
        if (from != null) {
            where.append(" AND o.created_at >= ?");
            params.add(Timestamp.valueOf(from.atStartOfDay()));
        }
        if (to != null) {
            where.append(" AND o.created_at < ?");
            params.add(Timestamp.valueOf(to.plusDays(1).atStartOfDay()));
        }
        if (q != null && !q.isBlank()) {
            where.append(" AND (o.order_number ILIKE ? OR o.ship_phone ILIKE ? OR o.customer_phone ILIKE ?)");
            String like = "%" + q.trim() + "%";
            params.add(like);
            params.add(like);
            params.add(like);
        }

        String countSql = "SELECT COUNT(*) FROM orders o" + where;
        int total;
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(countSql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                total = rs.getInt(1);
            }
        }

        String dataSql = """
                SELECT o.order_id, o.order_number, o.customer_id, o.ship_name, o.ship_phone, o.ship_city,
                       o.subtotal, o.shipping_fee, o.discount_amount, o.total_amount,
                       o.payment_method, o.payment_status, o.order_status,
                       o.courier_name, o.tracking_number, o.created_at, o.updated_at
                FROM orders o
                """ + where + " ORDER BY o.created_at DESC LIMIT ? OFFSET ?";
        List<Map<String, Object>> items = new ArrayList<>();
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(dataSql)) {
            bind(ps, params);
            ps.setInt(params.size() + 1, size);
            ps.setInt(params.size() + 2, (page - 1) * size);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(mapOrderSummary(rs));
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("page", page);
        result.put("size", size);
        result.put("total", total);
        return result;
    }

    public Map<String, Object> getDetail(long orderId) throws SQLException {
        try (Connection conn = DBConnection.getConnection()) {
            Map<String, Object> order = loadHeader(conn, orderId);
            if (order == null) {
                return null;
            }
            order.put("customer", loadCustomer(conn, ((Number) order.get("customerId")).longValue()));
            order.put("items", loadItems(conn, orderId));
            order.put("payments", loadPayments(conn, orderId));
            order.put("statusHistory", loadHistory(conn, orderId));
            return order;
        }
    }

    public Map<String, Object> lockOrder(Connection conn, long orderId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT order_id FROM orders WHERE order_id = ? FOR UPDATE")) {
            ps.setLong(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
            }
        }
        return loadHeader(conn, orderId);
    }

    public void updateStatus(Connection conn, long orderId, String newStatus, String comments,
                             String courierName, String trackingNumber) throws SQLException {
        StringBuilder sql = new StringBuilder("""
                UPDATE orders SET
                    order_status = ?,
                    updated_at = now()
                """);
        List<Object> params = new ArrayList<>();
        params.add(newStatus);

        switch (newStatus) {
            case "CONFIRMED" -> sql.append(", confirmed_at = COALESCE(confirmed_at, now())");
            case "DISPATCHED" -> {
                sql.append(", dispatched_at = COALESCE(dispatched_at, now())");
                if (courierName != null && !courierName.isBlank()) {
                    sql.append(", courier_name = ?");
                    params.add(courierName.trim());
                }
                if (trackingNumber != null && !trackingNumber.isBlank()) {
                    sql.append(", tracking_number = ?");
                    params.add(trackingNumber.trim());
                }
            }
            case "DELIVERED" -> sql.append(", delivered_at = COALESCE(delivered_at, now())");
            case "CANCELLED" -> {
                sql.append(", cancelled_at = COALESCE(cancelled_at, now())");
                if (comments != null && !comments.isBlank()) {
                    sql.append(", cancel_reason = ?");
                    params.add(comments.trim());
                }
            }
            case "RETURNED" -> {
                // no dedicated returned_at column; keep admin trail via history
            }
            default -> {
            }
        }

        sql.append(" WHERE order_id = ?");
        params.add(orderId);

        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                Object v = params.get(i);
                if (v instanceof Long l) {
                    ps.setLong(i + 1, l);
                } else {
                    ps.setString(i + 1, String.valueOf(v));
                }
            }
            ps.executeUpdate();
        }
    }

    public void insertStatusHistory(Connection conn, long orderId, String oldStatus, String newStatus,
                                    Integer adminId, String comments) throws SQLException {
        String sql = """
                INSERT INTO order_status_history (order_id, old_status, new_status, changed_by_admin_id, comments)
                VALUES (?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, orderId);
            if (oldStatus == null) {
                ps.setNull(2, Types.VARCHAR);
            } else {
                ps.setString(2, oldStatus);
            }
            ps.setString(3, newStatus);
            if (adminId == null) {
                ps.setNull(4, Types.INTEGER);
            } else {
                ps.setInt(4, adminId);
            }
            ps.setString(5, comments);
            ps.executeUpdate();
        }
    }

    public void restoreStockForOrder(Connection conn, long orderId) throws SQLException {
        String sql = """
                UPDATE product_variants v
                SET stock_quantity = v.stock_quantity + oi.quantity,
                    updated_at = now()
                FROM order_items oi
                WHERE oi.order_id = ?
                  AND oi.variant_id = v.variant_id
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, orderId);
            ps.executeUpdate();
        }
    }

    /** COD delivery capture: orders.payment_status=PAID, payments.status=SUCCESS */
    public void markCodPaid(Connection conn, long orderId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE orders
                SET payment_status = 'PAID', updated_at = now()
                WHERE order_id = ? AND payment_method = 'COD'
                """)) {
            ps.setLong(1, orderId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE payments
                SET status = 'SUCCESS', updated_at = now()
                WHERE order_id = ?
                  AND method_code = 'COD'
                  AND status IN ('INITIATED', 'PENDING')
                """)) {
            ps.setLong(1, orderId);
            ps.executeUpdate();
        }
    }

    private Map<String, Object> loadHeader(Connection conn, long orderId) throws SQLException {
        String sql = """
                SELECT o.order_id, o.order_number, o.customer_id,
                       o.ship_name, o.ship_phone, o.ship_email, o.ship_address, o.ship_city,
                       o.ship_province, o.ship_postal_code,
                       o.subtotal, o.shipping_fee, o.discount_amount, o.total_amount,
                       o.payment_method, o.payment_status, o.order_status,
                       o.courier_name, o.tracking_number, o.customer_notes, o.admin_notes,
                       o.cancel_reason, o.confirmed_at, o.dispatched_at, o.delivered_at, o.cancelled_at,
                       o.created_at, o.updated_at,
                       o.customer_name, o.customer_phone, o.customer_address, o.customer_city
                FROM orders o
                WHERE o.order_id = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return mapOrderDetail(rs);
            }
        }
    }

    private Map<String, Object> loadCustomer(Connection conn, long customerId) throws SQLException {
        String sql = """
                SELECT customer_id, full_name, phone, email, total_orders, total_spent, last_order_at
                FROM customers WHERE customer_id = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, customerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("customerId", rs.getLong("customer_id"));
                c.put("fullName", rs.getString("full_name"));
                c.put("phone", rs.getString("phone"));
                c.put("email", rs.getString("email"));
                c.put("totalOrders", rs.getInt("total_orders"));
                c.put("totalSpent", rs.getBigDecimal("total_spent"));
                c.put("lastOrderAt", ts(rs.getTimestamp("last_order_at")));
                return c;
            }
        }
    }

    private List<Map<String, Object>> loadItems(Connection conn, long orderId) throws SQLException {
        String sql = """
                SELECT order_item_id, variant_id, product_name, variant_name, sku,
                       quantity, unit_price, line_total
                FROM order_items WHERE order_id = ? ORDER BY order_item_id
                """;
        List<Map<String, Object>> items = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("orderItemId", rs.getLong("order_item_id"));
                    m.put("variantId", rs.getLong("variant_id"));
                    m.put("productName", rs.getString("product_name"));
                    m.put("variantName", rs.getString("variant_name"));
                    m.put("sku", rs.getString("sku"));
                    m.put("quantity", rs.getInt("quantity"));
                    m.put("unitPrice", rs.getBigDecimal("unit_price"));
                    m.put("lineTotal", rs.getBigDecimal("line_total"));
                    items.add(m);
                }
            }
        }
        return items;
    }

    private List<Map<String, Object>> loadPayments(Connection conn, long orderId) throws SQLException {
        String sql = """
                SELECT payment_id, method_code, amount, currency, status, provider, provider_reference, created_at
                FROM payments WHERE order_id = ? ORDER BY payment_id
                """;
        List<Map<String, Object>> items = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("paymentId", rs.getLong("payment_id"));
                    m.put("methodCode", rs.getString("method_code"));
                    m.put("amount", rs.getBigDecimal("amount"));
                    m.put("currency", rs.getString("currency"));
                    m.put("status", rs.getString("status"));
                    m.put("provider", rs.getString("provider"));
                    m.put("providerReference", rs.getString("provider_reference"));
                    m.put("createdAt", ts(rs.getTimestamp("created_at")));
                    items.add(m);
                }
            }
        }
        return items;
    }

    private List<Map<String, Object>> loadHistory(Connection conn, long orderId) throws SQLException {
        String sql = """
                SELECT history_id, old_status, new_status, changed_by_admin_id, comments, changed_at
                FROM order_status_history WHERE order_id = ? ORDER BY changed_at ASC, history_id ASC
                """;
        List<Map<String, Object>> items = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("historyId", rs.getLong("history_id"));
                    m.put("oldStatus", rs.getString("old_status"));
                    m.put("newStatus", rs.getString("new_status"));
                    int adminId = rs.getInt("changed_by_admin_id");
                    m.put("changedByAdminId", rs.wasNull() ? null : adminId);
                    m.put("comments", rs.getString("comments"));
                    m.put("changedAt", ts(rs.getTimestamp("changed_at")));
                    items.add(m);
                }
            }
        }
        return items;
    }

    private Map<String, Object> mapOrderSummary(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("orderId", rs.getLong("order_id"));
        m.put("orderNumber", rs.getString("order_number"));
        m.put("customerId", rs.getLong("customer_id"));
        m.put("shipName", rs.getString("ship_name"));
        m.put("shipPhone", rs.getString("ship_phone"));
        m.put("shipCity", rs.getString("ship_city"));
        m.put("subtotal", rs.getBigDecimal("subtotal"));
        m.put("shippingFee", rs.getBigDecimal("shipping_fee"));
        m.put("discountAmount", rs.getBigDecimal("discount_amount"));
        m.put("totalAmount", rs.getBigDecimal("total_amount"));
        m.put("paymentMethod", rs.getString("payment_method"));
        m.put("paymentStatus", rs.getString("payment_status"));
        m.put("orderStatus", rs.getString("order_status"));
        m.put("courierName", rs.getString("courier_name"));
        m.put("trackingNumber", rs.getString("tracking_number"));
        m.put("createdAt", ts(rs.getTimestamp("created_at")));
        m.put("updatedAt", ts(rs.getTimestamp("updated_at")));
        return m;
    }

    private Map<String, Object> mapOrderDetail(ResultSet rs) throws SQLException {
        Map<String, Object> m = mapOrderSummary(rs);
        m.put("shipEmail", rs.getString("ship_email"));
        m.put("shipAddress", rs.getString("ship_address"));
        m.put("shipProvince", rs.getString("ship_province"));
        m.put("shipPostalCode", rs.getString("ship_postal_code"));
        m.put("customerNotes", rs.getString("customer_notes"));
        m.put("adminNotes", rs.getString("admin_notes"));
        m.put("cancelReason", rs.getString("cancel_reason"));
        m.put("confirmedAt", ts(rs.getTimestamp("confirmed_at")));
        m.put("dispatchedAt", ts(rs.getTimestamp("dispatched_at")));
        m.put("deliveredAt", ts(rs.getTimestamp("delivered_at")));
        m.put("cancelledAt", ts(rs.getTimestamp("cancelled_at")));
        m.put("customerName", rs.getString("customer_name"));
        m.put("customerPhone", rs.getString("customer_phone"));
        m.put("customerAddress", rs.getString("customer_address"));
        m.put("customerCity", rs.getString("customer_city"));
        return m;
    }

    private static String ts(Timestamp t) {
        return t == null ? null : t.toInstant().toString();
    }

    private void bind(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            Object v = params.get(i);
            if (v instanceof Integer n) {
                ps.setInt(i + 1, n);
            } else if (v instanceof BigDecimal bd) {
                ps.setBigDecimal(i + 1, bd);
            } else if (v instanceof Timestamp ts) {
                ps.setTimestamp(i + 1, ts);
            } else {
                ps.setString(i + 1, String.valueOf(v));
            }
        }
    }
}
