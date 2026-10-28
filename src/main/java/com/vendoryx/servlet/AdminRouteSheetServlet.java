package com.vendoryx.servlet;

import com.vendoryx.dao.OrderDAO;
import com.vendoryx.util.CorsUtil;
import com.vendoryx.util.JsonUtil;
import com.vendoryx.util.RoleGuard;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * GET /api/admin/orders/route-sheet?dayId=&cityId=&status=&from=&to=
 * ADMIN / DEVELOPER only.
 */
public class AdminRouteSheetServlet extends HttpServlet {

    private static final List<String> DEFAULT_OPEN_STATUSES = List.of(
            "PENDING", "APPROVED", "PROCESSING", "PACKED", "OUT_FOR_DELIVERY"
    );

    private static final Set<String> ALLOWED_STATUSES = Set.of(
            "PENDING", "APPROVED", "PROCESSING", "PACKED",
            "OUT_FOR_DELIVERY", "DELIVERED", "CANCELLED"
    );

    private final OrderDAO orderDAO = new OrderDAO();

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        CorsUtil.apply(req, resp);
        resp.setStatus(HttpServletResponse.SC_OK);
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        CorsUtil.apply(request, response);
        if (!RoleGuard.requireRole(request, response, "ADMIN", "DEVELOPER")) {
            return;
        }

        try {
            Integer dayId = JsonUtil.queryInt(request, "dayId");
            if (dayId == null) {
                JsonUtil.writeError(response, HttpServletResponse.SC_BAD_REQUEST, "dayId is required");
                return;
            }

            Integer cityId = JsonUtil.queryInt(request, "cityId");
            LocalDate from = parseDate(JsonUtil.queryText(request, "from"));
            LocalDate to = parseDate(JsonUtil.queryText(request, "to"));

            List<String> statuses;
            try {
                statuses = parseStatuses(JsonUtil.queryText(request, "status"));
            } catch (IllegalArgumentException e) {
                JsonUtil.writeError(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
                return;
            }

            Map<String, Object> sheet = orderDAO.getRouteSheet(dayId, cityId, statuses, from, to);
            if (sheet == null) {
                JsonUtil.writeError(response, HttpServletResponse.SC_NOT_FOUND, "Visit day not found");
                return;
            }

            JsonUtil.writeSuccess(response, "Route sheet loaded", sheet);
        } catch (DateTimeParseException e) {
            JsonUtil.writeError(response, HttpServletResponse.SC_BAD_REQUEST,
                    "from/to must be YYYY-MM-DD");
        } catch (SQLException e) {
            e.printStackTrace();
            JsonUtil.writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Database error: " + e.getMessage());
        }
    }

    private LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return LocalDate.parse(raw);
    }

    private List<String> parseStatuses(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_OPEN_STATUSES;
        }
        List<String> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            String status = part.trim().toUpperCase(Locale.ROOT);
            if (status.isEmpty()) {
                continue;
            }
            if (!ALLOWED_STATUSES.contains(status)) {
                throw new IllegalArgumentException("Invalid status: " + status);
            }
            if (!out.contains(status)) {
                out.add(status);
            }
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("status must include at least one value");
        }
        return out;
    }
}
