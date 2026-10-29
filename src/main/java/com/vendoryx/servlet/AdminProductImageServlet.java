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
 * POST   /api/admin/products/images — add image
 * PUT    /api/admin/products/images?id= — update (primary / sort / alt)
 * DELETE /api/admin/products/images?id= — hard delete image row
 */
public class AdminProductImageServlet extends HttpServlet {

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
        if (!RoleGuard.requireAdmin(request, response)) {
            return;
        }
        try {
            JsonObject body = JsonUtil.parseObject(JsonUtil.readBody(request));
            Map<String, Object> fields = AdminProductServlet.parseImageObject(body);
            if (fields.get("productId") == null) {
                JsonUtil.writeError(response, HttpServletResponse.SC_BAD_REQUEST, "productId is required");
                return;
            }
            if (fields.get("imageUrl") == null) {
                JsonUtil.writeError(response, HttpServletResponse.SC_BAD_REQUEST, "imageUrl is required");
                return;
            }
            Map<String, Object> created = productDAO.createImage(fields);
            auditDAO.log(RoleGuard.getAdminId(request), "IMAGE_CREATE", "product_image",
                    ((Number) created.get("imageId")).longValue(), fields);
            JsonUtil.writeSuccess(response, "Image created", created);
        } catch (IllegalArgumentException e) {
            JsonUtil.writeError(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        } catch (JsonSyntaxException e) {
            JsonUtil.writeError(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid JSON body");
        } catch (SQLException e) {
            e.printStackTrace();
            String msg = e.getMessage() != null ? e.getMessage() : "Database error";
            if (msg.contains("uq_images_one_primary")) {
                JsonUtil.writeError(response, HttpServletResponse.SC_BAD_REQUEST,
                        "Only one primary image allowed per product");
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
                id = JsonUtil.longVal(body, "imageId");
                if (id == null) {
                    id = JsonUtil.longVal(body, "image_id");
                }
            }
            if (id == null || id <= 0) {
                JsonUtil.writeError(response, HttpServletResponse.SC_BAD_REQUEST, "image id is required");
                return;
            }
            Map<String, Object> fields = AdminProductServlet.parseImageObject(body);
            Map<String, Object> updated = productDAO.updateImage(id, fields);
            if (updated == null) {
                JsonUtil.writeError(response, HttpServletResponse.SC_NOT_FOUND, "Image not found");
                return;
            }
            auditDAO.log(RoleGuard.getAdminId(request), "IMAGE_UPDATE", "product_image", id, fields);
            JsonUtil.writeSuccess(response, "Image updated", updated);
        } catch (NumberFormatException e) {
            JsonUtil.writeError(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid image id");
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

    @Override
    protected void doDelete(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        CorsUtil.apply(request, response);
        if (!RoleGuard.requireAdmin(request, response)) {
            return;
        }
        try {
            Long id = queryLong(request, "id");
            if (id == null || id <= 0) {
                JsonUtil.writeError(response, HttpServletResponse.SC_BAD_REQUEST, "id query param is required");
                return;
            }
            boolean deleted = productDAO.deleteImage(id);
            if (!deleted) {
                JsonUtil.writeError(response, HttpServletResponse.SC_NOT_FOUND, "Image not found");
                return;
            }
            auditDAO.log(RoleGuard.getAdminId(request), "IMAGE_DELETE", "product_image", id, Map.of("deleted", true));
            JsonUtil.writeSuccess(response, "Image deleted", Map.of("imageId", id));
        } catch (NumberFormatException e) {
            JsonUtil.writeError(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid image id");
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