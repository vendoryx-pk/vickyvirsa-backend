package com.vendoryx.servlet;

import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.vendoryx.service.BusinessException;
import com.vendoryx.service.OrderService;
import com.vendoryx.util.CorsUtil;
import com.vendoryx.util.JsonUtil;
import com.vendoryx.util.RoleGuard;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Map;

/**
 * PUT /api/admin/orders/status?id=
 * Body: { status, customerNote?, internalNote?, courierName?, trackingNumber? }
 * Legacy aliases: comments → internalNote; cancelReason → customerNote (cancel only).
 * CANCELLED requires customerNote.
 */
public class AdminOrderStatusServlet extends HttpServlet {

    private final OrderService orderService = new OrderService();

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        CorsUtil.apply(req, resp);
        resp.setStatus(HttpServletResponse.SC_OK);
    }

    @Override
    protected void doPut(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        CorsUtil.apply(request, response);
        if (!RoleGuard.requireAdmin(request, response)) {
            return;
        }

        try {
            String idRaw = request.getParameter("id");
            if (idRaw == null || idRaw.isBlank()) {
                JsonUtil.writeError(response, HttpServletResponse.SC_BAD_REQUEST, "id query param is required");
                return;
            }
            long orderId = Long.parseLong(idRaw.trim());
            Integer adminId = RoleGuard.getAdminId(request);
            if (adminId == null) {
                JsonUtil.writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized");
                return;
            }

            JsonObject body = JsonUtil.parseObject(JsonUtil.readBody(request));
            String newStatus = JsonUtil.textAny(body, "status", "newStatus");
            String courierName = JsonUtil.textAny(body, "courierName", "courier_name");
            String trackingNumber = JsonUtil.textAny(body, "trackingNumber", "tracking_number");

            String customerNote = firstNonBlank(
                    JsonUtil.textAny(body, "customerNote", "customer_note"),
                    JsonUtil.textAny(body, "cancelReason", "cancel_reason"));
            String internalNote = firstNonBlank(
                    JsonUtil.textAny(body, "internalNote", "internal_note"),
                    JsonUtil.text(body, "comments"));

            Map<String, Object> updated = orderService.changeAdminOrderStatus(
                    orderId, newStatus, customerNote, internalNote, courierName, trackingNumber, adminId);
            JsonUtil.writeSuccess(response, "Order status updated", updated);
        } catch (NumberFormatException e) {
            JsonUtil.writeError(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid order id");
        } catch (BusinessException e) {
            JsonUtil.writeError(response, e.getHttpStatus(), e.getMessage());
        } catch (JsonSyntaxException e) {
            JsonUtil.writeError(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid JSON body");
        } catch (SQLException e) {
            e.printStackTrace();
            JsonUtil.writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Database error: " + e.getMessage());
        }
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a.trim();
        }
        if (b != null && !b.isBlank()) {
            return b.trim();
        }
        return null;
    }
}
