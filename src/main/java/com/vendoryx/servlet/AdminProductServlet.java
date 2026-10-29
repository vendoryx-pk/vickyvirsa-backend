package com.vendoryx.servlet;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * /api/admin/products
 * GET list (?q &amp; categoryId &amp; active &amp; page) or ?id=
 * POST create (OWNER) — product + variants[] + images[]
 * PUT ?id= update fields (STAFF: descriptions only; OWNER: full incl. isActive)
 * DELETE ?id= soft delete (OWNER)
 */
public class AdminProductServlet extends HttpServlet {

    private final ProductDAO productDAO = new ProductDAO();
    private final AdminAuditDAO auditDAO = new AdminAuditDAO();

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
            if (categoryId == null) {
                categoryId = JsonUtil.queryInt(request, "category");
            }
            String q = JsonUtil.queryText(request, "q");
            Boolean activeFilter = parseActiveFilter(request);
            int page = JsonUtil.pageOrDefault(request, 1);
            int size = JsonUtil.sizeOrDefault(request, 20, 100);

            JsonUtil.writeSuccess(response, "Products loaded",
                    productDAO.listAdminCatalog(categoryId, q, activeFilter, page, size));
        } catch (NumberFormatException e) {
            JsonUtil.writeError(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid product id");
        } catch (SQLException e) {
            e.printStackTrace();
            JsonUtil.writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Database error: " + e.getMessage());
        }
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
            Map<String, Object> fields = parseProductBody(body, true);
            Map<String, Object> created = productDAO.createAdminProduct(fields);
            auditDAO.log(RoleGuard.getAdminId(request), "PRODUCT_CREATE", "product",
                    ((Number) created.get("productId")).longValue(),
                    Map.of("slug", created.get("slug"), "productName", created.get("productName")));
            JsonUtil.writeSuccess(response, "Product created", created);
        } catch (IllegalArgumentException e) {
            JsonUtil.writeError(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        } catch (JsonSyntaxException e) {
            JsonUtil.writeError(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid JSON body");
        } catch (SQLException e) {
            e.printStackTrace();
            String msg = e.getMessage() != null ? e.getMessage() : "Database error";
            if (msg.contains("product_variants_sku_key") || msg.contains("duplicate key")) {
                JsonUtil.writeError(response, HttpServletResponse.SC_BAD_REQUEST, "Duplicate sku or slug");
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
            if (id == null || id <= 0) {
                JsonUtil.writeError(response, HttpServletResponse.SC_BAD_REQUEST, "id query param is required");
                return;
            }
            JsonObject body = JsonUtil.parseObject(JsonUtil.readBody(request));
            boolean owner = RoleGuard.isOwner(request);

            if (!owner) {
                if (JsonUtil.hasNonNull(body, "isActive") || JsonUtil.hasNonNull(body, "active")) {
                    JsonUtil.writeError(response, HttpServletResponse.SC_FORBIDDEN,
                            "Only OWNER can activate/deactivate products");
                    return;
                }
                if (body != null && body.has("variants") && !body.get("variants").isJsonNull()) {
                    JsonUtil.writeError(response, HttpServletResponse.SC_FORBIDDEN,
                            "STAFF cannot update variants here; use /api/admin/products/variants or /stock");
                    return;
                }
            }

            Map<String, Object> fields = parseProductBody(body, false);
            if (!owner) {
                fields = staffProductFields(fields);
            }

            List<Map<String, Object>> nested = owner ? castVariants(body) : null;
            Map<String, Object> updated = productDAO.updateAdminProduct(id, fields, nested);
            if (updated == null) {
                JsonUtil.writeError(response, HttpServletResponse.SC_NOT_FOUND, "Product not found");
                return;
            }
            auditDAO.log(RoleGuard.getAdminId(request), "PRODUCT_UPDATE", "product", id, fields);
            JsonUtil.writeSuccess(response, "Product updated", updated);
        } catch (NumberFormatException e) {
            JsonUtil.writeError(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid product id");
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
        if (!RoleGuard.requireOwner(request, response)) {
            return;
        }
        try {
            Long id = queryLong(request, "id");
            if (id == null || id <= 0) {
                JsonUtil.writeError(response, HttpServletResponse.SC_BAD_REQUEST, "id query param is required");
                return;
            }
            Map<String, Object> deleted = productDAO.softDeleteProduct(id);
            if (deleted == null) {
                JsonUtil.writeError(response, HttpServletResponse.SC_NOT_FOUND, "Product not found");
                return;
            }
            auditDAO.log(RoleGuard.getAdminId(request), "PRODUCT_DEACTIVATE", "product", id,
                    Map.of("isActive", false));
            JsonUtil.writeSuccess(response, "Product deactivated", deleted);
        } catch (NumberFormatException e) {
            JsonUtil.writeError(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid product id");
        } catch (SQLException e) {
            e.printStackTrace();
            JsonUtil.writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Database error: " + e.getMessage());
        }
    }

    private static Boolean parseActiveFilter(HttpServletRequest request) {
        String active = JsonUtil.queryText(request, "active");
        if (active != null) {
            if ("true".equalsIgnoreCase(active) || "1".equals(active)) {
                return Boolean.TRUE;
            }
            if ("false".equalsIgnoreCase(active) || "0".equals(active)) {
                return Boolean.FALSE;
            }
        }
        String includeInactive = JsonUtil.queryText(request, "includeInactive");
        if ("true".equalsIgnoreCase(includeInactive)) {
            return null;
        }
        if ("false".equalsIgnoreCase(includeInactive)) {
            return Boolean.TRUE;
        }
        // Admin default: show all
        return null;
    }

    private static Map<String, Object> staffProductFields(Map<String, Object> fields) {
        Map<String, Object> allowed = new LinkedHashMap<>();
        for (String key : List.of("shortDescription", "description", "brand", "specs",
                "metaTitle", "metaDescription", "productName")) {
            if (fields.containsKey(key)) {
                allowed.put(key, fields.get(key));
            }
        }
        return allowed;
    }

    private Map<String, Object> parseProductBody(JsonObject body, boolean forCreate) {
        Map<String, Object> fields = new LinkedHashMap<>();
        if (body == null) {
            if (forCreate) {
                throw new IllegalArgumentException("JSON body is required");
            }
            return fields;
        }
        putText(fields, body, "productName", "product_name", "name");
        putText(fields, body, "slug");
        putText(fields, body, "shortDescription", "short_description");
        putText(fields, body, "description");
        putText(fields, body, "brand");
        putText(fields, body, "metaTitle", "meta_title");
        putText(fields, body, "metaDescription", "meta_description");

        Integer categoryId = JsonUtil.integer(body, "categoryId");
        if (categoryId == null) {
            categoryId = JsonUtil.integer(body, "category_id");
        }
        if (categoryId != null) {
            fields.put("categoryId", categoryId);
        }

        if (body.has("specs") && !body.get("specs").isJsonNull()) {
            fields.put("specs", JsonUtil.gson().fromJson(body.get("specs"), Object.class));
        }
        Boolean featured = JsonUtil.bool(body, "isFeatured");
        if (featured == null) {
            featured = JsonUtil.bool(body, "featured");
        }
        if (featured != null) {
            fields.put("isFeatured", featured);
        }
        Boolean active = JsonUtil.bool(body, "isActive");
        if (active == null) {
            active = JsonUtil.bool(body, "active");
        }
        if (active != null) {
            fields.put("isActive", active);
        }
        Integer sortOrder = JsonUtil.integer(body, "sortOrder");
        if (sortOrder == null) {
            sortOrder = JsonUtil.integer(body, "sort_order");
        }
        if (sortOrder != null) {
            fields.put("sortOrder", sortOrder);
        }

        List<Map<String, Object>> variants = castVariants(body);
        if (variants != null) {
            fields.put("variants", variants);
        }
        List<Map<String, Object>> images = castImages(body);
        if (images != null) {
            fields.put("images", images);
        }
        return fields;
    }

    private static List<Map<String, Object>> castVariants(JsonObject body) {
        if (body == null || !body.has("variants") || body.get("variants").isJsonNull()) {
            return null;
        }
        JsonArray arr = body.getAsJsonArray("variants");
        List<Map<String, Object>> list = new ArrayList<>();
        for (JsonElement el : arr) {
            list.add(parseVariantObject(el.getAsJsonObject()));
        }
        return list;
    }

    private static List<Map<String, Object>> castImages(JsonObject body) {
        if (body == null || !body.has("images") || body.get("images").isJsonNull()) {
            return null;
        }
        JsonArray arr = body.getAsJsonArray("images");
        List<Map<String, Object>> list = new ArrayList<>();
        for (JsonElement el : arr) {
            list.add(parseImageObject(el.getAsJsonObject()));
        }
        return list;
    }

    public static Map<String, Object> parseVariantObject(JsonObject o) {
        Map<String, Object> m = new LinkedHashMap<>();
        Long variantId = JsonUtil.longVal(o, "variantId");
        if (variantId == null) {
            variantId = JsonUtil.longVal(o, "variant_id");
        }
        if (variantId != null) {
            m.put("variantId", variantId);
        }
        Long productId = JsonUtil.longVal(o, "productId");
        if (productId == null) {
            productId = JsonUtil.longVal(o, "product_id");
        }
        if (productId != null) {
            m.put("productId", productId);
        }
        putText(m, o, "sku");
        putText(m, o, "variantName", "variant_name");
        if (o.has("attributes") && !o.get("attributes").isJsonNull()) {
            m.put("attributes", JsonUtil.gson().fromJson(o.get("attributes"), Object.class));
        }
        BigDecimal price = JsonUtil.money(o, "price");
        if (price != null) {
            m.put("price", price);
        }
        if (o.has("compareAtPrice") || o.has("compare_at_price")) {
            BigDecimal compare = JsonUtil.money(o, "compareAtPrice");
            if (compare == null) {
                compare = JsonUtil.money(o, "compare_at_price");
            }
            m.put("compareAtPrice", compare);
        }
        Integer stock = JsonUtil.integer(o, "stockQuantity");
        if (stock == null) {
            stock = JsonUtil.integer(o, "stock_quantity");
        }
        if (stock != null) {
            m.put("stockQuantity", stock);
        }
        Integer low = JsonUtil.integer(o, "lowStockThreshold");
        if (low == null) {
            low = JsonUtil.integer(o, "low_stock_threshold");
        }
        if (low != null) {
            m.put("lowStockThreshold", low);
        }
        Boolean active = JsonUtil.bool(o, "isActive");
        if (active == null) {
            active = JsonUtil.bool(o, "active");
        }
        if (active != null) {
            m.put("isActive", active);
        }
        Integer sortOrder = JsonUtil.integer(o, "sortOrder");
        if (sortOrder == null) {
            sortOrder = JsonUtil.integer(o, "sort_order");
        }
        if (sortOrder != null) {
            m.put("sortOrder", sortOrder);
        }
        return m;
    }

    public static Map<String, Object> parseImageObject(JsonObject o) {
        Map<String, Object> m = new LinkedHashMap<>();
        Long imageId = JsonUtil.longVal(o, "imageId");
        if (imageId == null) {
            imageId = JsonUtil.longVal(o, "image_id");
        }
        if (imageId != null) {
            m.put("imageId", imageId);
        }
        Long productId = JsonUtil.longVal(o, "productId");
        if (productId == null) {
            productId = JsonUtil.longVal(o, "product_id");
        }
        if (productId != null) {
            m.put("productId", productId);
        }
        if (o.has("variantId") || o.has("variant_id")) {
            Long variantId = JsonUtil.longVal(o, "variantId");
            if (variantId == null) {
                variantId = JsonUtil.longVal(o, "variant_id");
            }
            m.put("variantId", variantId);
        }
        putText(m, o, "imageUrl", "image_url", "url");
        putText(m, o, "altText", "alt_text", "altText");
        Integer sortOrder = JsonUtil.integer(o, "sortOrder");
        if (sortOrder == null) {
            sortOrder = JsonUtil.integer(o, "sort_order");
        }
        if (sortOrder != null) {
            m.put("sortOrder", sortOrder);
        }
        Boolean primary = JsonUtil.bool(o, "isPrimary");
        if (primary == null) {
            primary = JsonUtil.bool(o, "primary");
        }
        if (primary != null) {
            m.put("isPrimary", primary);
        }
        return m;
    }

    private static void putText(Map<String, Object> fields, JsonObject body, String... keys) {
        String value = JsonUtil.textAny(body, keys);
        if (value != null) {
            fields.put(keys[0], value);
        } else if (body != null && body.has(keys[0]) && body.get(keys[0]).isJsonNull()) {
            fields.put(keys[0], null);
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