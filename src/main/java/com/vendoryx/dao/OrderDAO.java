package com.vendoryx.dao;

import com.vendoryx.config.DBConnection;
import com.vendoryx.model.Order;
import com.vendoryx.model.OrderItem;
import com.vendoryx.model.OrderStatusHistory;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class OrderDAO {

    private static final String ORDER_SELECT = """
            SELECT o.order_id, o.order_number, o.shop_id, s.shop_name, s.shop_code,
                   c.city_name, d.day_name, o.placed_by_user_id,
                   o.total_amount, o.tax_amount, o.discount_amount, o.final_amount,
                   o.order_status, o.payment_status, o.notes, o.created_at
            FROM orders o
            JOIN shops s ON o.shop_id = s.shop_id
            JOIN cities c ON s.city_id = c.city_id
            JOIN visit_days d ON s.day_id = d.day_id
            """;

    public record ShopOrderContext(int shopId, String status, BigDecimal creditLimit) {}

    public ShopOrderContext getShopContext(Connection conn, int shopId) throws SQLException {
        String sql = "SELECT shop_id, status, COALESCE(credit_limit, 0) AS credit_limit FROM shops WHERE shop_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, shopId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new ShopOrderContext(
                            rs.getInt("shop_id"),
                            rs.getString("status"),
                            rs.getBigDecimal("credit_limit")
                    );
                }
            }
        }
        return null;
    }

    public BigDecimal sumOutstandingCredit(Connection conn, int shopId) throws SQLException {
        String sql = """
                SELECT COALESCE(SUM(final_amount), 0)
                FROM orders
                WHERE shop_id = ?
                  AND order_status IN ('APPROVED', 'PROCESSING', 'PACKED', 'OUT_FOR_DELIVERY', 'DELIVERED')
                  AND payment_status IN ('UNPAID', 'PARTIAL')
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, shopId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBigDecimal(1);
            }
        }
    }

    public int insertOrder(Connection conn, int shopId, int userId, BigDecimal total,
                           BigDecimal tax, BigDecimal discount, BigDecimal finalAmount,
                           String notes) throws SQLException {
        String sql = """
                INSERT INTO orders (
                    shop_id, placed_by_user_id, total_amount, tax_amount, discount_amount,
                    final_amount, order_status, payment_status, notes
                ) VALUES (?, ?, ?, ?, ?, ?, 'PENDING', 'UNPAID', ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, shopId);
            ps.setInt(2, userId);
            ps.setBigDecimal(3, total);
            ps.setBigDecimal(4, tax);
            ps.setBigDecimal(5, discount);
            ps.setBigDecimal(6, finalAmount);
            ps.setString(7, notes);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getInt(1);
                }
            }
        }
        throw new SQLException("Failed to create order");
    }

    public void insertOrderItem(Connection conn, int orderId, int productId, int quantity,
                                BigDecimal unitPrice, BigDecimal subtotal) throws SQLException {
        String sql = """
                INSERT INTO order_items (order_id, product_id, quantity, unit_price_at_purchase, subtotal)
                VALUES (?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, orderId);
            ps.setInt(2, productId);
            ps.setInt(3, quantity);
            ps.setBigDecimal(4, unitPrice);
            ps.setBigDecimal(5, subtotal);
            ps.executeUpdate();
        }
    }

    /**
     * Guest COD order: no shop / user. Requires sql/guest_checkout_columns.sql.
     * Uses existing columns: total_amount, final_amount, order_status, payment_status, notes.
     */
    public int insertGuestOrder(Connection conn, String customerName, String phone, String address,
                                String city, String notes, BigDecimal total) throws SQLException {
        String sql = """
                INSERT INTO orders (
                    shop_id, placed_by_user_id,
                    total_amount, tax_amount, discount_amount, final_amount,
                    order_status, payment_status, payment_method, notes,
                    customer_name, customer_phone, customer_address, customer_city
                ) VALUES (
                    NULL, NULL,
                    ?, 0, 0, ?,
                    'NEW', 'UNPAID', 'COD', ?,
                    ?, ?, ?, ?
                )
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setBigDecimal(1, total);
            ps.setBigDecimal(2, total);
            ps.setString(3, notes);
            ps.setString(4, customerName);
            ps.setString(5, phone);
            ps.setString(6, address);
            ps.setString(7, city);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getInt(1);
                }
            }
        }
        throw new SQLException("Failed to create guest order");
    }

    public void insertGuestOrderItem(Connection conn, int orderId, int productId, String productName,
                                     int quantity, BigDecimal unitPrice, BigDecimal lineTotal)
            throws SQLException {
        String sql = """
                INSERT INTO order_items (
                    order_id, product_id, product_name, quantity, unit_price_at_purchase, subtotal
                ) VALUES (?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, orderId);
            ps.setInt(2, productId);
            ps.setString(3, productName);
            ps.setInt(4, quantity);
            ps.setBigDecimal(5, unitPrice);
            ps.setBigDecimal(6, lineTotal);
            ps.executeUpdate();
        }
    }

    public Order getGuestOrderHeader(Connection conn, int orderId) throws SQLException {
        String sql = """
                SELECT order_id, order_number, final_amount, order_status, payment_method,
                       customer_name, customer_phone, customer_address, customer_city, notes, created_at
                FROM orders
                WHERE order_id = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Order o = new Order();
                    o.setOrderId(rs.getInt("order_id"));
                    o.setOrderNumber(rs.getString("order_number"));
                    o.setFinalAmount(rs.getBigDecimal("final_amount"));
                    o.setTotalAmount(rs.getBigDecimal("final_amount"));
                    o.setOrderStatus(rs.getString("order_status"));
                    o.setPaymentMethod(rs.getString("payment_method"));
                    o.setCustomerName(rs.getString("customer_name"));
                    o.setCustomerPhone(rs.getString("customer_phone"));
                    o.setCustomerAddress(rs.getString("customer_address"));
                    o.setCustomerCity(rs.getString("customer_city"));
                    o.setNotes(rs.getString("notes"));
                    o.setCreatedAt(rs.getTimestamp("created_at"));
                    return o;
                }
            }
        }
        return null;
    }

    public void insertStatusHistory(Connection conn, int orderId, String oldStatus, String newStatus,
                                    Integer changedByUserId, String comments) throws SQLException {
        String sql = """
                INSERT INTO order_status_history (order_id, old_status, new_status, changed_by_user_id, comments)
                VALUES (?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, orderId);
            if (oldStatus == null) {
                ps.setNull(2, Types.VARCHAR);
            } else {
                ps.setString(2, oldStatus);
            }
            ps.setString(3, newStatus);
            if (changedByUserId == null) {
                ps.setNull(4, Types.INTEGER);
            } else {
                ps.setInt(4, changedByUserId);
            }
            ps.setString(5, comments);
            ps.executeUpdate();
        }
    }

    public Order getOrderHeader(Connection conn, int orderId) throws SQLException {
        String sql = ORDER_SELECT + " WHERE o.order_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapOrder(rs);
                }
            }
        }
        return null;
    }

    public Order lockOrder(Connection conn, int orderId) throws SQLException {
        String lockSql = "SELECT order_id FROM orders WHERE order_id = ? FOR UPDATE";
        try (PreparedStatement ps = conn.prepareStatement(lockSql)) {
            ps.setInt(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
            }
        }
        return getOrderHeader(conn, orderId);
    }

    public void updateStatus(Connection conn, int orderId, String newStatus) throws SQLException {
        String sql = "UPDATE orders SET order_status = ? WHERE order_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, newStatus);
            ps.setInt(2, orderId);
            ps.executeUpdate();
        }
    }

    public List<OrderItem> getItems(Connection conn, int orderId) throws SQLException {
        String sql = """
                SELECT oi.order_id, oi.product_id, p.product_name, p.sku, oi.quantity,
                       oi.unit_price_at_purchase, oi.subtotal
                FROM order_items oi
                JOIN products p ON oi.product_id = p.product_id
                WHERE oi.order_id = ?
                ORDER BY oi.product_id
                """;
        List<OrderItem> items = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    OrderItem item = new OrderItem();
                    item.setOrderId(rs.getInt("order_id"));
                    item.setProductId(rs.getInt("product_id"));
                    item.setProductName(rs.getString("product_name"));
                    item.setSku(rs.getString("sku"));
                    item.setQuantity(rs.getInt("quantity"));
                    item.setUnitPriceAtPurchase(rs.getBigDecimal("unit_price_at_purchase"));
                    item.setSubtotal(rs.getBigDecimal("subtotal"));
                    items.add(item);
                }
            }
        }
        return items;
    }

    public List<OrderStatusHistory> getHistory(Connection conn, int orderId) throws SQLException {
        String sql = """
                SELECT order_id, old_status, new_status, changed_by_user_id, comments, changed_at
                FROM order_status_history
                WHERE order_id = ?
                ORDER BY changed_at ASC
                """;
        List<OrderStatusHistory> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    OrderStatusHistory h = new OrderStatusHistory();
                    h.setOrderId(rs.getInt("order_id"));
                    h.setOldStatus(rs.getString("old_status"));
                    h.setNewStatus(rs.getString("new_status"));
                    int uid = rs.getInt("changed_by_user_id");
                    h.setChangedByUserId(rs.wasNull() ? null : uid);
                    h.setComments(rs.getString("comments"));
                    h.setChangedAt(rs.getTimestamp("changed_at"));
                    list.add(h);
                }
            }
        }
        return list;
    }

    public boolean deductStock(Connection conn, int productId, int qty) throws SQLException {
        String sql = """
                UPDATE inventory
                SET stock_quantity = stock_quantity - ?
                WHERE product_id = ? AND stock_quantity >= ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, qty);
            ps.setInt(2, productId);
            ps.setInt(3, qty);
            return ps.executeUpdate() > 0;
        }
    }

    public void restoreStock(Connection conn, int productId, int qty) throws SQLException {
        String sql = """
                UPDATE inventory
                SET stock_quantity = stock_quantity + ?
                WHERE product_id = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, qty);
            ps.setInt(2, productId);
            ps.executeUpdate();
        }
    }

    public Map<String, Object> listForShops(List<Integer> shopIds, Integer shopIdFilter,
                                            int page, int size) throws SQLException {
        if (shopIds == null || shopIds.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("items", List.of());
            empty.put("page", page);
            empty.put("size", size);
            empty.put("total", 0);
            return empty;
        }

        StringBuilder where = new StringBuilder(" WHERE o.shop_id IN (");
        List<Object> params = new ArrayList<>();
        for (int i = 0; i < shopIds.size(); i++) {
            where.append(i == 0 ? "?" : ",?");
            params.add(shopIds.get(i));
        }
        where.append(")");
        if (shopIdFilter != null) {
            where.append(" AND o.shop_id = ?");
            params.add(shopIdFilter);
        }
        return pagedList(where.toString(), params, page, size);
    }

    public Map<String, Object> listAdmin(String status, Integer cityId, Integer dayId,
                                         LocalDate from, LocalDate to, int page, int size) throws SQLException {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            where.append(" AND o.order_status = ?");
            params.add(status.trim().toUpperCase());
        }
        if (cityId != null) {
            where.append(" AND s.city_id = ?");
            params.add(cityId);
        }
        if (dayId != null) {
            where.append(" AND s.day_id = ?");
            params.add(dayId);
        }
        if (from != null) {
            where.append(" AND o.created_at >= ?");
            params.add(Timestamp.valueOf(from.atStartOfDay()));
        }
        if (to != null) {
            where.append(" AND o.created_at < ?");
            params.add(Timestamp.valueOf(to.plusDays(1).atStartOfDay()));
        }
        return pagedList(where.toString(), params, page, size);
    }

    public Order getDetail(int orderId) throws SQLException {
        try (Connection conn = DBConnection.getConnection()) {
            Order order = getOrderHeader(conn, orderId);
            if (order == null) {
                return null;
            }
            order.setItems(getItems(conn, orderId));
            order.setStatusHistory(getHistory(conn, orderId));
            return order;
        }
    }

    /**
     * Route sheet for a visit day: shops that have matching orders, with items.
     * Uses 2 queries (day lookup + one JOIN of orders/items) — no N+1.
     */
    public Map<String, Object> getRouteSheet(int dayId, Integer cityId, List<String> statuses,
                                             LocalDate from, LocalDate to) throws SQLException {
        String dayName;
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT day_name FROM visit_days WHERE day_id = ?")) {
            ps.setInt(1, dayId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                dayName = rs.getString("day_name");
            }
        }

        StringBuilder sql = new StringBuilder("""
                SELECT o.order_id, o.order_number, o.order_status, o.created_at, o.notes, o.final_amount,
                       s.shop_id, s.shop_code, s.shop_name, s.contact_person, s.phone, s.address,
                       c.city_name,
                       oi.product_id, p.sku, p.product_name, p.unit_of_measure,
                       oi.quantity, oi.unit_price_at_purchase, oi.subtotal
                FROM orders o
                JOIN shops s ON o.shop_id = s.shop_id
                JOIN cities c ON s.city_id = c.city_id
                LEFT JOIN order_items oi ON oi.order_id = o.order_id
                LEFT JOIN products p ON oi.product_id = p.product_id
                WHERE s.day_id = ?
                """);
        List<Object> params = new ArrayList<>();
        params.add(dayId);

        sql.append(" AND o.order_status IN (");
        for (int i = 0; i < statuses.size(); i++) {
            sql.append(i == 0 ? "?" : ",?");
            params.add(statuses.get(i));
        }
        sql.append(")");

        if (cityId != null) {
            sql.append(" AND s.city_id = ?");
            params.add(cityId);
        }
        if (from != null) {
            sql.append(" AND o.created_at >= ?");
            params.add(Timestamp.valueOf(from.atStartOfDay()));
        }
        if (to != null) {
            sql.append(" AND o.created_at < ?");
            params.add(Timestamp.valueOf(to.plusDays(1).atStartOfDay()));
        }
        sql.append(" ORDER BY s.shop_name ASC, o.created_at ASC, oi.product_id ASC");

        Map<Integer, Map<String, Object>> shopsById = new LinkedHashMap<>();
        Map<Integer, Map<String, Object>> ordersById = new LinkedHashMap<>();
        Map<Integer, Map<String, Object>> productTotalsById = new LinkedHashMap<>();
        int orderCount = 0;
        BigDecimal grandTotal = BigDecimal.ZERO;

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int shopId = rs.getInt("shop_id");
                    Map<String, Object> shop = shopsById.get(shopId);
                    if (shop == null) {
                        shop = new LinkedHashMap<>();
                        shop.put("shopId", shopId);
                        shop.put("shopCode", rs.getString("shop_code"));
                        shop.put("shopName", rs.getString("shop_name"));
                        shop.put("contactPerson", rs.getString("contact_person"));
                        shop.put("phone", rs.getString("phone"));
                        shop.put("address", rs.getString("address"));
                        shop.put("cityName", rs.getString("city_name"));
                        shop.put("shopTotal", BigDecimal.ZERO);
                        shop.put("orders", new ArrayList<Map<String, Object>>());
                        shopsById.put(shopId, shop);
                    }

                    int orderId = rs.getInt("order_id");
                    Map<String, Object> order = ordersById.get(orderId);
                    if (order == null) {
                        order = new LinkedHashMap<>();
                        order.put("orderId", orderId);
                        order.put("orderNumber", rs.getString("order_number"));
                        order.put("status", rs.getString("order_status"));
                        order.put("createdAt", rs.getTimestamp("created_at"));
                        order.put("notes", rs.getString("notes"));
                        BigDecimal finalAmount = rs.getBigDecimal("final_amount");
                        if (finalAmount == null) {
                            finalAmount = BigDecimal.ZERO;
                        }
                        order.put("finalAmount", finalAmount);
                        order.put("items", new ArrayList<Map<String, Object>>());
                        ordersById.put(orderId, order);

                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> shopOrders =
                                (List<Map<String, Object>>) shop.get("orders");
                        shopOrders.add(order);

                        shop.put("shopTotal",
                                ((BigDecimal) shop.get("shopTotal")).add(finalAmount));
                        grandTotal = grandTotal.add(finalAmount);
                        orderCount++;
                    }

                    int productId = rs.getInt("product_id");
                    if (!rs.wasNull()) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("productId", productId);
                        item.put("sku", rs.getString("sku"));
                        item.put("productName", rs.getString("product_name"));
                        item.put("unitOfMeasure", rs.getString("unit_of_measure"));
                        int qty = rs.getInt("quantity");
                        item.put("quantity", qty);
                        item.put("unitPrice", rs.getBigDecimal("unit_price_at_purchase"));
                        item.put("subtotal", rs.getBigDecimal("subtotal"));

                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> items =
                                (List<Map<String, Object>>) order.get("items");
                        items.add(item);

                        Map<String, Object> total = productTotalsById.get(productId);
                        if (total == null) {
                            total = new LinkedHashMap<>();
                            total.put("sku", rs.getString("sku"));
                            total.put("productName", rs.getString("product_name"));
                            total.put("unitOfMeasure", rs.getString("unit_of_measure"));
                            total.put("totalQuantity", 0);
                            productTotalsById.put(productId, total);
                        }
                        total.put("totalQuantity", ((Integer) total.get("totalQuantity")) + qty);
                    }
                }
            }
        }

        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("dayId", dayId);
        filters.put("cityId", cityId);
        filters.put("status", statuses);
        filters.put("from", from != null ? from.toString() : null);
        filters.put("to", to != null ? to.toString() : null);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dayName", dayName);
        result.put("generatedAt", Instant.now().toString());
        result.put("filters", filters);
        result.put("orderCount", orderCount);
        result.put("grandTotal", grandTotal);
        result.put("shops", new ArrayList<>(shopsById.values()));
        result.put("productTotals", new ArrayList<>(productTotalsById.values()));
        return result;
    }

    private Map<String, Object> pagedList(String where, List<Object> params, int page, int size) throws SQLException {
        String countSql = """
                SELECT COUNT(*)
                FROM orders o
                JOIN shops s ON o.shop_id = s.shop_id
                JOIN cities c ON s.city_id = c.city_id
                JOIN visit_days d ON s.day_id = d.day_id
                """ + where;

        int total;
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(countSql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                total = rs.getInt(1);
            }
        }

        String dataSql = ORDER_SELECT + where + " ORDER BY o.created_at DESC LIMIT ? OFFSET ?";
        List<Order> items = new ArrayList<>();
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(dataSql)) {
            bind(ps, params);
            ps.setInt(params.size() + 1, size);
            ps.setInt(params.size() + 2, (page - 1) * size);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(mapOrder(rs));
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

    private void bind(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            Object value = params.get(i);
            if (value instanceof Integer) {
                ps.setInt(i + 1, (Integer) value);
            } else if (value instanceof Timestamp) {
                ps.setTimestamp(i + 1, (Timestamp) value);
            } else {
                ps.setString(i + 1, String.valueOf(value));
            }
        }
    }

    private Order mapOrder(ResultSet rs) throws SQLException {
        Order o = new Order();
        o.setOrderId(rs.getInt("order_id"));
        o.setOrderNumber(rs.getString("order_number"));
        o.setShopId(rs.getInt("shop_id"));
        o.setShopName(rs.getString("shop_name"));
        o.setShopCode(rs.getString("shop_code"));
        o.setCityName(rs.getString("city_name"));
        o.setDayName(rs.getString("day_name"));
        o.setPlacedByUserId(rs.getInt("placed_by_user_id"));
        o.setTotalAmount(rs.getBigDecimal("total_amount"));
        o.setTaxAmount(rs.getBigDecimal("tax_amount"));
        o.setDiscountAmount(rs.getBigDecimal("discount_amount"));
        o.setFinalAmount(rs.getBigDecimal("final_amount"));
        o.setOrderStatus(rs.getString("order_status"));
        o.setPaymentStatus(rs.getString("payment_status"));
        o.setNotes(rs.getString("notes"));
        o.setCreatedAt(rs.getTimestamp("created_at"));
        return o;
    }
}
