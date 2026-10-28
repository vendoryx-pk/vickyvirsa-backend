package com.vendoryx.dao;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * VickyVirsa guest COD checkout persistence (Neon B2C schema).
 */
public class GuestCheckoutDAO {

    public record CheckoutVariant(
            long variantId,
            long productId,
            String productName,
            String variantName,
            String sku,
            BigDecimal price,
            int stockQuantity
    ) {}

    public record ShippingQuote(BigDecimal fee, BigDecimal freeOver, String matchedCity) {}

    public record CreatedOrder(long orderId, String orderNumber, BigDecimal totalAmount,
                               String orderStatus, String paymentMethod, String paymentStatus,
                               BigDecimal subtotal, BigDecimal shippingFee) {}

    /** Cheapest active variant for product; locks row. Null if product/variant missing. */
    public CheckoutVariant lockCheapestActiveVariant(Connection conn, long productId) throws SQLException {
        String sql = """
                SELECT v.variant_id, v.product_id, p.product_name, v.variant_name, v.sku,
                       v.price, v.stock_quantity
                FROM product_variants v
                JOIN products p ON p.product_id = v.product_id
                WHERE v.product_id = ?
                  AND v.is_active = TRUE
                  AND p.is_active = TRUE
                ORDER BY v.price ASC, v.sort_order ASC, v.variant_id ASC
                LIMIT 1
                FOR UPDATE OF v
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, productId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new CheckoutVariant(
                        rs.getLong("variant_id"),
                        rs.getLong("product_id"),
                        rs.getString("product_name"),
                        rs.getString("variant_name"),
                        rs.getString("sku"),
                        rs.getBigDecimal("price"),
                        rs.getInt("stock_quantity")
                );
            }
        }
    }

    /** Atomic stock decrement. Returns false if insufficient stock / inactive. */
    public boolean deductVariantStock(Connection conn, long variantId, int qty) throws SQLException {
        String sql = """
                UPDATE product_variants
                SET stock_quantity = stock_quantity - ?,
                    updated_at = now()
                WHERE variant_id = ?
                  AND is_active = TRUE
                  AND stock_quantity >= ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, qty);
            ps.setLong(2, variantId);
            ps.setInt(3, qty);
            return ps.executeUpdate() == 1;
        }
    }

    /**
     * City rate preferred; falls back to active city='*'.
     * Null if neither found.
     */
    public ShippingQuote resolveShipping(Connection conn, String city) throws SQLException {
        String sql = """
                SELECT city, fee, free_over
                FROM shipping_rates
                WHERE is_active = TRUE
                  AND (LOWER(city) = LOWER(?) OR city = '*')
                ORDER BY CASE WHEN LOWER(city) = LOWER(?) THEN 0 ELSE 1 END,
                         rate_id ASC
                LIMIT 1
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, city);
            ps.setString(2, city);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new ShippingQuote(
                        rs.getBigDecimal("fee"),
                        rs.getBigDecimal("free_over"),
                        rs.getString("city")
                );
            }
        }
    }

    /** Find-or-create by E.164 phone (unique). Updates name on conflict. */
    public long findOrCreateCustomer(Connection conn, String fullName, String phoneE164) throws SQLException {
        String sql = """
                INSERT INTO customers (full_name, phone)
                VALUES (?, ?)
                ON CONFLICT (phone) DO UPDATE
                SET full_name = EXCLUDED.full_name,
                    updated_at = now()
                RETURNING customer_id
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, fullName);
            ps.setString(2, phoneE164);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    public void bumpCustomerStats(Connection conn, long customerId, BigDecimal orderTotal) throws SQLException {
        String sql = """
                UPDATE customers
                SET total_orders = total_orders + 1,
                    total_spent = total_spent + ?,
                    last_order_at = now(),
                    updated_at = now()
                WHERE customer_id = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBigDecimal(1, orderTotal);
            ps.setLong(2, customerId);
            ps.executeUpdate();
        }
    }

    public CreatedOrder insertOrder(Connection conn, long customerId, String shipName, String shipPhone,
                                    String shipEmail, String shipAddress, String shipCity,
                                    BigDecimal subtotal, BigDecimal shippingFee, BigDecimal discount,
                                    BigDecimal totalAmount, String customerNotes) throws SQLException {
        String sql = """
                INSERT INTO orders (
                    customer_id,
                    ship_name, ship_phone, ship_email, ship_address, ship_city,
                    subtotal, shipping_fee, discount_amount, total_amount,
                    payment_method, payment_status, order_status, customer_notes,
                    customer_name, customer_phone, customer_address, customer_city
                ) VALUES (
                    ?,
                    ?, ?, ?, ?, ?,
                    ?, ?, ?, ?,
                    'COD', 'UNPAID', 'PENDING', ?,
                    ?, ?, ?, ?
                )
                RETURNING order_id, order_number, total_amount, order_status, payment_method, payment_status,
                          subtotal, shipping_fee
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            ps.setLong(i++, customerId);
            ps.setString(i++, shipName);
            ps.setString(i++, shipPhone);
            if (shipEmail == null || shipEmail.isBlank()) {
                ps.setNull(i++, Types.VARCHAR);
            } else {
                ps.setString(i++, shipEmail);
            }
            ps.setString(i++, shipAddress);
            ps.setString(i++, shipCity);
            ps.setBigDecimal(i++, subtotal);
            ps.setBigDecimal(i++, shippingFee);
            ps.setBigDecimal(i++, discount);
            ps.setBigDecimal(i++, totalAmount);
            ps.setString(i++, customerNotes);
            ps.setString(i++, shipName);
            ps.setString(i++, shipPhone);
            ps.setString(i++, shipAddress);
            ps.setString(i, shipCity);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("Failed to insert guest order");
                }
                return new CreatedOrder(
                        rs.getLong("order_id"),
                        rs.getString("order_number"),
                        rs.getBigDecimal("total_amount"),
                        rs.getString("order_status"),
                        rs.getString("payment_method"),
                        rs.getString("payment_status"),
                        rs.getBigDecimal("subtotal"),
                        rs.getBigDecimal("shipping_fee")
                );
            }
        }
    }

    public void insertOrderItem(Connection conn, long orderId, CheckoutVariant variant,
                                int quantity, BigDecimal unitPrice, BigDecimal lineTotal)
            throws SQLException {
        String sql = """
                INSERT INTO order_items (
                    order_id, variant_id, product_name, variant_name, sku,
                    quantity, unit_price, line_total
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, orderId);
            ps.setLong(2, variant.variantId());
            ps.setString(3, variant.productName());
            ps.setString(4, variant.variantName());
            ps.setString(5, variant.sku());
            ps.setInt(6, quantity);
            ps.setBigDecimal(7, unitPrice);
            ps.setBigDecimal(8, lineTotal);
            ps.executeUpdate();
        }
    }

    public void insertStatusHistory(Connection conn, long orderId, String newStatus, String comments)
            throws SQLException {
        String sql = """
                INSERT INTO order_status_history (order_id, old_status, new_status, changed_by_admin_id, comments)
                VALUES (?, NULL, ?, NULL, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, orderId);
            ps.setString(2, newStatus);
            ps.setString(3, comments);
            ps.executeUpdate();
        }
    }

    public void insertCodPayment(Connection conn, long orderId, BigDecimal amount) throws SQLException {
        String sql = """
                INSERT INTO payments (order_id, method_code, amount, currency, status)
                VALUES (?, 'COD', ?, 'PKR', 'INITIATED')
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, orderId);
            ps.setBigDecimal(2, amount);
            ps.executeUpdate();
        }
    }

    public Map<String, Object> toResponse(CreatedOrder order) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", order.orderId());
        data.put("orderNumber", order.orderNumber());
        data.put("subtotal", order.subtotal());
        data.put("shippingFee", order.shippingFee());
        data.put("total", order.totalAmount());
        data.put("status", order.orderStatus());
        data.put("paymentMethod", order.paymentMethod());
        data.put("paymentStatus", order.paymentStatus());
        return data;
    }
}
