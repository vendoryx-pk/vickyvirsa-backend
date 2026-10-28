package com.vendoryx.servlet;

import com.vendoryx.dao.AdminOrderDAO;
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
import java.util.Map;

/** GET /api/admin/orders — OWNER/STAFF */
public class AdminOrderServlet extends HttpServlet {

    private final AdminOrderDAO adminOrderDAO = new AdminOrderDAO();

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        CorsUtil.apply(req, resp);
        resp.setStatus(HttpServletResponse.SC_OK);
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        CorsUtil.apply(request, response);
        if (!RoleGuard.requireAdmin(request, response)) {
            return;
        }

        try {
            Long id = queryLong(request, "id");
            if (id != null) {
                Map<String, Object> order = adminOrderDAO.getDetail(id);
                if (order == null) {
                    JsonUtil.writeError(response, HttpServletResponse.SC_NOT_FOUND, "Order not found");
                    return;
                }
                JsonUtil.writeSuccess(response, "Order loaded", order);
                return;
            }

            String status = JsonUtil.queryText(request, "status");
            String q = JsonUtil.queryText(request, "q");
            LocalDate from = parseDate(JsonUtil.queryText(request, "from"));
            LocalDate to = parseDate(JsonUtil.queryText(request, "to"));
            int page = JsonUtil.pageOrDefault(request, 1);
            int size = JsonUtil.sizeOrDefault(request, 20, 100);

            JsonUtil.writeSuccess(response, "Orders loaded",
                    adminOrderDAO.list(status, from, to, q, page, size));
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

    private Long queryLong(HttpServletRequest request, String name) {
        String raw = request.getParameter(name);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return Long.parseLong(raw.trim());
    }
}
