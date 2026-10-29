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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * POST /api/admin/products/variants — add variant (OWNER)
 * PUT  /api/admin/products/variants?id= — edit; isActive=false deactivates
 *      OWNER: prices; STAFF: stock / descriptions / attributes (no price / no deactivate)
 */
public class AdminProductVariantServlet extends HttpServlet {

    private final ProductDAO productDAO = new ProductDAO();
    private final AdminAuditDAO auditDAO = new AdminAuditDAO();

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        CorsUtil.apply(req, resp);
        resp.setStatus(HttpServletResponse.SC_OK);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        CorsUtil.apply(request, response);
        if (!RoleGuard.requireOwner(request, response)) {
            return;
        }
        try {
            JsonObject body = JsonUtil.parseObject(JsonUtil.readBody(request));
            Map<String, Object> fields = AdminProductServlet.parseVariantObject(body);
            if (fields.get("productId") == null) {
                JsonUtil.writeError(response, HttpServletResponse.SC_BAD_REQUEST, "productId is required");
                return;
            }
            Map<String, Object> created = productDAO.createVariant(fields);
            if (created == null) {
                JsonUtil.writeError(response, HttpServletResponse.SC_NOT_FOUND, "Product not found");
                return;
            }
            auditDAO.log(RoleGuard.getAdminId(request), "VARIANT_CREATE", "product_variant",
                    ((Number) created.get("variantId")).longValue(), fields);
            JsonUtil.writeSuccess(response, "Variant created", created);
        } catch (IllegalArgumentException e) {
            JsonUtil.writeError(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        } catch (JsonSyntaxException e) {
            JsonUtil.writeError(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid JSON body");
        } catch (SQLException e) {
            e.printStackTrace();
            String msg = e.getMessage() != null ? e.getMessage() : "Database error";
            if (msg.contains("product_variants_sku_key")) {
                JsonUtil.writeError(response, HttpServletResponse.SC_BAD_REQUEST, "Duplicate sku");
            } else {
                JsonUtil.writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Database error: " + msg);
            }
        }
    }

    @Override
    protected void doPut(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        CorsUtil.apply(request, response);
        if (!RoleGuard.requireAdmin(request, response)) {
            return;
        }
        try {
            Long id = queryLong(request, "id");
            JsonObject body = JsonUtil.parseObject(JsonUtil.readBody(request));
            if (id == null || id <= 0) {
                id = JsonUtil.longVal(body, "variantId");
                if (id == null) {
                    id = JsonUtil.longVal(body, "variant_id");
                }
            }
            if (id == null || id <= 0) {
                JsonUtil.writeError(response, HttpServletResponse.SC_BAD_REQUEST, "variant id is required");
                return;
            }

            boolean owner = RoleGuard.isOwner(request);
            Map<String, Object> fields = AdminProductServlet.parseVariantObject(body);

            if (!owner) {
                if (fields.containsKey("price") || fields.containsKey("compareAtPrice")) {
                    JsonUtil.writeError(response, HttpServletResponse.SC_FORBIDDEN,
                            "Only OWNER can change price/compareAtPrice");
                    return;
                }
                if (fields.containsKey("isActive") && Boolean.FALSE.equals(fields.get("isActive"))) {
                    JsonUtil.writeError(response, HttpServletResponse.SC_FORBIDDEN,
                            "Only OWNER can deactivate variants");
                    return;
                }
                fields = staffVariantFields(fields);
            }

            Map<String, Object> updated = productDAO.updateVariant(id, fields, owner);
            if (updated == null) {
                JsonUtil.writeError(response, HttpServletResponse.SC_NOT_FOUND, "Variant not found");
                return;
            }
            auditDAO.log(RoleGuard.getAdminId(request), "VARIANT_UPDATE", "product_variant", id, fields);
            JsonUtil.writeSuccess(response, "Variant updated", updated);
        } catch (NumberFormatException e) {
            JsonUtil.writeError(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid variant id");
        } catch (IllegalArgumentException e) {
            JsonUtil.writeError(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        } catch (JsonSyntaxException e) {
            JsonUtil.writeError(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid JSON body");
        } catch (SQLException e) {
            e.printStackTrace();
            JsonUtil.writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Database error: " + e.getMessage());
        }
    }

    private static Map<String, Object> staffVariantFields(Map<String, Object> fields) {
        Map<String, Object> allowed = new LinkedHashMap<>();
        for (String key : new String[]{
                "variantName", "attributes", "stockQuantity", "lowStockThreshold", "sortOrder", "sku"
        }) {
            if (fields.containsKey(key)) {
                allowed.put(key, fields.get(key));
            }
        }
        return allowed;
    }

    private Long queryLong(HttpServletRequest request, String name) {
        String raw = request.getParameter(name);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return Long.parseLong(raw.trim());
    }
}