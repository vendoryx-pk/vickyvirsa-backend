package com.vendoryx.service;

import com.vendoryx.config.DBConnection;
import com.vendoryx.dao.AdminOrderDAO;
import com.vendoryx.dao.GuestCheckoutDAO;
import com.vendoryx.dao.GuestCheckoutDAO.CheckoutVariant;
import com.vendoryx.dao.GuestCheckoutDAO.CreatedOrder;
import com.vendoryx.dao.GuestCheckoutDAO.ShippingQuote;
import com.vendoryx.dao.OrderDAO;
import com.vendoryx.dao.ProductDAO;
import com.vendoryx.model.Order;
import com.vendoryx.model.OrderItem;
import jakarta.servlet.http.HttpServletResponse;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class OrderService {

    /** Allowed transitions among orders.order_status_check values. */
    private static final Map<String, Set<String>> ADMIN_TRANSITIONS = Map.of(
            "PENDING", Set.of("CONFIRMED", "CANCELLED"),
            "CONFIRMED", Set.of("DISPATCHED", "CANCELLED"),
            "DISPATCHED", Set.of("DELIVERED", "RETURNED"),
            "DELIVERED", Set.of("RETURNED"),
            "CANCELLED", Set.of(),
            "RETURNED", Set.of()
    );

    private final OrderDAO orderDAO = new OrderDAO();
    private final ProductDAO productDAO = new ProductDAO();
    private final GuestCheckoutDAO guestCheckoutDAO = new GuestCheckoutDAO();
    private final AdminOrderDAO adminOrderDAO = new AdminOrderDAO();

    /**
     * Guest B2C COD checkout against VickyVirsa Neon schema.
     * Request shape unchanged: customerName, phone, address, city, notes?, items[{productId, quantity}].
     * productId resolves to cheapest active variant; client prices ignored.
     */
    public Map<String, Object> placeGuestOrder(String customerName, String phone, String address,
                                               String city, String notes,
                                               List<Map.Entry<Integer, Integer>> rawItems)
            throws BusinessException, SQLException {

        String name = requireText(customerName, "customerName");
        String phoneRaw = requireText(phone, "phone");
        String phoneE164 = normalizePhoneE164(phoneRaw);
        if (phoneE164 == null) {
            throw new BusinessException(HttpServletResponse.SC_BAD_REQUEST,
                    "Invalid phone format (use 03XXXXXXXXX or +92XXXXXXXXXX)");
        }
        String addr = requireText(address, "address");
        String cityNorm = requireText(city, "city");
        String notesNorm = notes != null && !notes.isBlank() ? notes.trim() : null;

        if (rawItems == null || rawItems.isEmpty()) {
            throw new BusinessException(HttpServletResponse.SC_BAD_REQUEST, "items must not be empty");
        }

        Map<Long, Integer> merged = new LinkedHashMap<>();
        for (Map.Entry<Integer, Integer> entry : rawItems) {
            Integer productId = entry.getKey();
            Integer qty = entry.getValue();
            if (productId == null || productId <= 0 || qty == null || qty < 1 || qty > 10) {
                throw new BusinessException(HttpServletResponse.SC_BAD_REQUEST,
                        "Each item needs valid productId and quantity 1..10");
            }
            long pid = productId.longValue();
            merged.merge(pid, qty, Integer::sum);
            if (merged.get(pid) > 10) {
                throw new BusinessException(HttpServletResponse.SC_BAD_REQUEST,
                        "quantity 1..10 per product");
            }
        }

        try (Connection conn = DBConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                List<CheckoutVariant> lines = new ArrayList<>();
                List<Integer> quantities = new ArrayList<>();
                BigDecimal subtotal = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

                for (Map.Entry<Long, Integer> e : merged.entrySet()) {
                    long productId = e.getKey();
                    int qty = e.getValue();
                    CheckoutVariant variant = guestCheckoutDAO.lockCheapestActiveVariant(conn, productId);
                    if (variant == null) {
                        throw new BusinessException(HttpServletResponse.SC_BAD_REQUEST,
                                "Product not found or inactive: " + productId);
                    }
                    if (!guestCheckoutDAO.deductVariantStock(conn, variant.variantId(), qty)) {
                        throw new BusinessException(HttpServletResponse.SC_BAD_REQUEST, "out of stock");
                    }
                    BigDecimal unit = variant.price().setScale(2, RoundingMode.HALF_UP);
                    subtotal = subtotal.add(unit.multiply(BigDecimal.valueOf(qty)));
                    lines.add(variant);
                    quantities.add(qty);
                }
                subtotal = subtotal.setScale(2, RoundingMode.HALF_UP);

                ShippingQuote shipping = guestCheckoutDAO.resolveShipping(conn, cityNorm);
                if (shipping == null) {
                    throw new BusinessException(HttpServletResponse.SC_BAD_REQUEST,
                            "Shipping not available for city: " + cityNorm);
                }
                BigDecimal shippingFee = shipping.fee().setScale(2, RoundingMode.HALF_UP);
                if (shipping.freeOver() != null && subtotal.compareTo(shipping.freeOver()) >= 0) {
                    shippingFee = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
                }
                BigDecimal discount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
                BigDecimal total = subtotal.add(shippingFee).subtract(discount)
                        .setScale(2, RoundingMode.HALF_UP);

                long customerId = guestCheckoutDAO.findOrCreateCustomer(conn, name, phoneE164);

                CreatedOrder created = guestCheckoutDAO.insertOrder(
                        conn, customerId, name, phoneE164, null, addr, cityNorm,
                        subtotal, shippingFee, discount, total, notesNorm);

                for (int i = 0; i < lines.size(); i++) {
                    CheckoutVariant variant = lines.get(i);
                    int qty = quantities.get(i);
                    BigDecimal unit = variant.price().setScale(2, RoundingMode.HALF_UP);
                    BigDecimal lineTotal = unit.multiply(BigDecimal.valueOf(qty))
                            .setScale(2, RoundingMode.HALF_UP);
                    guestCheckoutDAO.insertOrderItem(conn, created.orderId(), variant, qty, unit, lineTotal);
                }

                guestCheckoutDAO.insertStatusHistory(conn, created.orderId(), "PENDING",
                        "Guest COD order placed");
                guestCheckoutDAO.insertCodPayment(conn, created.orderId(), total);
                guestCheckoutDAO.bumpCustomerStats(conn, customerId, total);

                conn.commit();
                return guestCheckoutDAO.toResponse(created);
            } catch (BusinessException e) {
                conn.rollback();
                throw e;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    private static String requireText(String value, String field) throws BusinessException {
        if (value == null || value.isBlank()) {
            throw new BusinessException(HttpServletResponse.SC_BAD_REQUEST, field + " is required");
        }
        return value.trim();
    }

    /**
     * customers.chk_customer_phone requires {@code ^\\+[0-9]{10,15}$}.
     * Accepts 03XXXXXXXXX / 3XXXXXXXXX / 92... / +92...
     */
    private static String normalizePhoneE164(String phone) {
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

    public Map<String, Object> placeOrder(int userId, List<Integer> tokenShopIds, int shopId,
                                          List<Map.Entry<Integer, Integer>> rawItems, String notes)
            throws BusinessException, SQLException {

        if (tokenShopIds == null || !tokenShopIds.contains(shopId)) {
            throw new BusinessException(HttpServletResponse.SC_FORBIDDEN, "Shop not allowed for this user");
        }
        if (rawItems == null || rawItems.isEmpty() || rawItems.size() > 100) {
            throw new BusinessException(HttpServletResponse.SC_BAD_REQUEST, "Items must be between 1 and 100");
        }

        Map<Integer, Integer> merged = new LinkedHashMap<>();
        for (Map.Entry<Integer, Integer> entry : rawItems) {
            Integer productId = entry.getKey();
            Integer qty = entry.getValue();
            if (productId == null || productId <= 0 || qty == null || qty < 1 || qty > 10000) {
                throw new BusinessException(HttpServletResponse.SC_BAD_REQUEST,
                        "Each item needs valid productId and quantity 1..10000");
            }
            merged.merge(productId, qty, Integer::sum);
        }

        try (Connection conn = DBConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                OrderDAO.ShopOrderContext shop = orderDAO.getShopContext(conn, shopId);
                if (shop == null) {
                    throw new BusinessException(HttpServletResponse.SC_NOT_FOUND, "Shop not found");
                }
                if (shop.status() == null || !"ACTIVE".equalsIgnoreCase(shop.status())) {
                    throw new BusinessException(HttpServletResponse.SC_FORBIDDEN, "Shop is not ACTIVE");
                }

                BigDecimal total = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
                Map<Integer, BigDecimal> prices = new HashMap<>();
                Map<Integer, Integer> quantities = new LinkedHashMap<>();

                for (Map.Entry<Integer, Integer> e : merged.entrySet()) {
                    int productId = e.getKey();
                    int qty = e.getValue();
                    BigDecimal price = productDAO.getActiveUnitPrice(conn, productId);
                    if (price == null) {
                        throw new BusinessException(HttpServletResponse.SC_BAD_REQUEST,
                                "Product not found or inactive: " + productId);
                    }
                    int stock = productDAO.getStock(conn, productId);
                    if (stock < qty) {
                        String name = productDAO.getProductName(conn, productId);
                        throw new BusinessException(HttpServletResponse.SC_CONFLICT,
                                "Insufficient stock for " + name);
                    }
                    prices.put(productId, price);
                    quantities.put(productId, qty);
                    total = total.add(price.multiply(BigDecimal.valueOf(qty)));
                }

                BigDecimal tax = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
                BigDecimal discount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
                BigDecimal finalAmount = total.setScale(2, RoundingMode.HALF_UP);

                int orderId = orderDAO.insertOrder(conn, shopId, userId, total, tax, discount, finalAmount, notes);
                for (Map.Entry<Integer, Integer> e : quantities.entrySet()) {
                    int productId = e.getKey();
                    int qty = e.getValue();
                    BigDecimal price = prices.get(productId);
                    BigDecimal subtotal = price.multiply(BigDecimal.valueOf(qty)).setScale(2, RoundingMode.HALF_UP);
                    orderDAO.insertOrderItem(conn, orderId, productId, qty, price, subtotal);
                }
                orderDAO.insertStatusHistory(conn, orderId, null, "PENDING", userId, "Order placed");

                Order created = orderDAO.getOrderHeader(conn, orderId);
                conn.commit();

                Map<String, Object> data = new LinkedHashMap<>();
                data.put("orderId", created.getOrderId());
                data.put("orderNumber", created.getOrderNumber());
                data.put("finalAmount", created.getFinalAmount());
                data.put("status", created.getOrderStatus());
                return data;
            } catch (BusinessException e) {
                conn.rollback();
                throw e;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    /**
     * Admin order status change (VickyVirsa).
     * Status values: PENDING, CONFIRMED, DISPATCHED, DELIVERED, CANCELLED, RETURNED.
     */
    public Map<String, Object> changeAdminOrderStatus(long orderId, String newStatusRaw, String comments,
                                                      String courierName, String trackingNumber,
                                                      String cancelReason, int adminId)
            throws BusinessException, SQLException {

        if (newStatusRaw == null || newStatusRaw.isBlank()) {
            throw new BusinessException(HttpServletResponse.SC_BAD_REQUEST, "status is required");
        }
        String newStatus = newStatusRaw.trim().toUpperCase();
        if (!ADMIN_TRANSITIONS.containsKey(newStatus)) {
            throw new BusinessException(HttpServletResponse.SC_BAD_REQUEST,
                    "Invalid status (allowed: PENDING, CONFIRMED, DISPATCHED, DELIVERED, CANCELLED, RETURNED)");
        }

        if ("DISPATCHED".equals(newStatus)) {
            if (courierName == null || courierName.isBlank()
                    || trackingNumber == null || trackingNumber.isBlank()) {
                throw new BusinessException(HttpServletResponse.SC_BAD_REQUEST,
                        "courierName and trackingNumber are required for DISPATCHED");
            }
        }

        try (Connection conn = DBConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                Map<String, Object> order = adminOrderDAO.lockOrder(conn, orderId);
                if (order == null) {
                    throw new BusinessException(HttpServletResponse.SC_NOT_FOUND, "Order not found");
                }

                String oldStatus = order.get("orderStatus") != null
                        ? order.get("orderStatus").toString().toUpperCase()
                        : "";
                Set<String> allowed = ADMIN_TRANSITIONS.getOrDefault(oldStatus, Set.of());
                if (!allowed.contains(newStatus)) {
                    throw new BusinessException(HttpServletResponse.SC_BAD_REQUEST,
                            "Invalid status transition: " + oldStatus + " -> " + newStatus);
                }

                String reason = cancelReason != null && !cancelReason.isBlank()
                        ? cancelReason.trim()
                        : comments;
                adminOrderDAO.updateStatus(conn, orderId, newStatus, reason, courierName, trackingNumber);
                adminOrderDAO.insertStatusHistory(conn, orderId, oldStatus, newStatus, adminId, comments);

                if (("CANCELLED".equals(newStatus) || "RETURNED".equals(newStatus))
                        && !oldStatus.equals(newStatus)) {
                    adminOrderDAO.restoreStockForOrder(conn, orderId);
                }

                if ("DELIVERED".equals(newStatus)) {
                    adminOrderDAO.markCodPaid(conn, orderId);
                }

                conn.commit();
                return adminOrderDAO.getDetail(orderId);
            } catch (BusinessException e) {
                conn.rollback();
                throw e;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    /** Legacy B2B status change — disabled on VickyVirsa schema. */
    public Order changeStatus(int orderId, String newStatusRaw, String comments, int changedByUserId)
            throws BusinessException, SQLException {
        throw new BusinessException(HttpServletResponse.SC_GONE,
                "B2B order status API removed — use PUT /api/admin/orders/status");
    }
}
