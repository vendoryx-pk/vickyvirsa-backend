package com.vendoryx.servlet;

import com.vendoryx.dao.ProductDAO;
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
 * GET /api/admin/products — list (variants included) or ?id=
 * OWNER / STAFF. Product CRUD later.
 */
public class AdminProductServlet extends HttpServlet {

    private final ProductDAO productDAO = new ProductDAO();

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
                Map<String, Object> product = productDAO.getAdminById(id);
                if (product == null) {
                    JsonUtil.writeError(response, HttpServletResponse.SC_NOT_FOUND, "Product not found");
                    return;
                }
                JsonUtil.writeSuccess(response, "Product loaded", product);
                return;
            }

            Integer categoryId = JsonUtil.queryInt(request, "categoryId");
            String q = JsonUtil.queryText(request, "q");
            boolean includeInactive = "true".equalsIgnoreCase(JsonUtil.queryText(request, "includeInactive"));
            int page = JsonUtil.pageOrDefault(request, 1);
            int size = JsonUtil.sizeOrDefault(request, 20, 100);

            JsonUtil.writeSuccess(response, "Products loaded",
                    productDAO.listAdminCatalog(categoryId, q, includeInactive, page, size));
        } catch (NumberFormatException e) {
            JsonUtil.writeError(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid product id");
        } catch (SQLException e) {
            e.printStackTrace();
            JsonUtil.writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Database error: " + e.getMessage());
        }
    }

    private Long queryLong(HttpServletRequest request, String name) {
        String raw = request.getParameter(name);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return Long.parseLong(raw.trim());
    }
}
