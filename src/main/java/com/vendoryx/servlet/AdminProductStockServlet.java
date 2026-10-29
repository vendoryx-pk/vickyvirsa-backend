package com.vendoryx.servlet;

import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.vendoryx.dao.AdminAuditDAO;
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
 * PUT /api/admin/products/stock
 * Body: { variantId, stockQuantity, lowStockThreshold }
 */
public class AdminProductStockServlet extends HttpServlet {

    private final ProductDAO productDAO = new ProductDAO();
    private final AdminAuditDAO auditDAO = new AdminAuditDAO();

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
            JsonObject body = JsonUtil.parseObject(JsonUtil.readBody(request));
            Integer variantId = JsonUtil.integer(body, "variantId");
            if (variantId == null) {
                variantId = JsonUtil.queryInt(request, "variantId");
            }
            Integer stockQuantity = JsonUtil.integer(body, "stockQuantity");
            Integer lowStockThreshold = JsonUtil.integer(body, "lowStockThreshold");

            if (variantId == null || variantId <= 0
                    || stockQuantity == null || stockQuantity < 0
                    || lowStockThreshold == null || lowStockThreshold < 0) {
                JsonUtil.writeError(response, HttpServletResponse.SC_BAD_REQUEST,
                        "variantId, stockQuantity (>=0) and lowStockThreshold (>=0) are required");
                return;
            }

            Map<String, Object> updated = productDAO.updateVariantStock(
                    variantId.longValue(), stockQuantity, lowStockThreshold);
            if (updated == null) {
                JsonUtil.writeError(response, HttpServletResponse.SC_NOT_FOUND, "Variant not found");
                return;
            }
            auditDAO.log(RoleGuard.getAdminId(request), "VARIANT_STOCK_UPDATE", "product_variant",
                    variantId.longValue(),
                    Map.of("stockQuantity", stockQuantity, "lowStockThreshold", lowStockThreshold));
            JsonUtil.writeSuccess(response, "Stock updated", updated);
        } catch (JsonSyntaxException e) {
            JsonUtil.writeError(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid JSON body");
        } catch (SQLException e) {
            e.printStackTrace();
            JsonUtil.writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Database error: " + e.getMessage());
        }
    }
}
