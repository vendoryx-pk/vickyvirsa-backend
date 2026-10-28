package com.vendoryx.dao;

import com.google.gson.JsonSyntaxException;
import com.vendoryx.config.DBConnection;
import com.vendoryx.model.Product;
import com.vendoryx.util.JsonUtil;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * VickyVirsa B2C product catalog (products + variants + images).
 * Admin/shop VendoryX methods kept as stubs until those APIs are migrated.
 */
public class ProductDAO {

    private static final String PRODUCT_SELECT = """
            SELECT p.product_id, p.slug, p.product_name, p.short_description, p.description,
                   p.brand, p.specs::text AS specs_json, p.is_active, p.is_featured, p.sort_order,
                   p.category_id, c.slug AS category_slug, c.category_name
            FROM products p
            JOIN categories c ON p.category_id = c.category_id
            """;

    /** Active products for public storefront (no N+1). */
    public List<Map<String, Object>> listActivePublic() throws SQLException {
        String sql = PRODUCT_SELECT + """
                WHERE p.is_active = TRUE
                ORDER BY p.sort_order ASC NULLS LAST, p.product_id ASC
                """;
        List<Map<String, Object>> products = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> row = mapProductRow(rs);
                products.add(row);
                ids.add((Long) row.get("productId"));
            }
        }
        attachVariantsAndImages(products, ids);
        return products;
    }

    /** One active product; null if missing/inactive. */
    public Map<String, Object> getActivePublicById(long productId) throws SQLException {
        String sql = PRODUCT_SELECT + " WHERE p.product_id = ? AND p.is_active = TRUE";
        Map<String, Object> product;
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, productId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                product = mapProductRow(rs);
            }
        }
        attachVariantsAndImages(List.of(product), List.of(productId));
        return product;
    }

    private Map<String, Object> mapProductRow(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        long productId = rs.getLong("product_id");
        m.put("productId", productId);
        m.put("slug", rs.getString("slug"));
        m.put("productName", rs.getString("product_name"));
        m.put("shortDescription", rs.getString("short_description"));
        m.put("description", rs.getString("description"));
        m.put("brand", rs.getString("brand"));
        m.put("specs", parseSpecs(rs.getString("specs_json")));
        m.put("isFeatured", rs.getBoolean("is_featured"));
        m.put("isActive", rs.getBoolean("is_active"));

        Map<String, Object> category = new LinkedHashMap<>();
        category.put("categoryId", rs.getInt("category_id"));
        category.put("slug", rs.getString("category_slug"));
        category.put("categoryName", rs.getString("category_name"));
        m.put("category", category);

        m.put("price", null);
        m.put("compareAtPrice", null);
        m.put("imageUrl", null);
        m.put("images", new ArrayList<Map<String, Object>>());
        m.put("variants", new ArrayList<Map<String, Object>>());
        return m;
    }

    private Object parseSpecs(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JsonUtil.gson().fromJson(json, Object.class);
        } catch (JsonSyntaxException e) {
            return json;
        }
    }

    private void attachVariantsAndImages(List<Map<String, Object>> products, List<Long> ids)
            throws SQLException {
        if (products.isEmpty() || ids.isEmpty()) {
            return;
        }

        Map<Long, List<Map<String, Object>>> variantsByProduct = loadVariants(ids);
        Map<Long, List<Map<String, Object>>> imagesByProduct = loadImages(ids);

        for (Map<String, Object> product : products) {
            Long productId = (Long) product.get("productId");
            List<Map<String, Object>> variants = variantsByProduct.getOrDefault(productId, List.of());
            List<Map<String, Object>> images = imagesByProduct.getOrDefault(productId, List.of());

            product.put("variants", variants);
            product.put("images", images);
            product.put("imageUrl", pickPrimaryImageUrl(images));
            applyMinVariantPricing(product, variants);
        }
    }

    private Map<Long, List<Map<String, Object>>> loadVariants(List<Long> ids) throws SQLException {
        String placeholders = ids.stream().map(i -> "?").collect(Collectors.joining(","));
        String sql = """
                SELECT variant_id, product_id, sku, variant_name, price, compare_at_price,
                       stock_quantity, is_active, sort_order
                FROM product_variants
                WHERE product_id IN (%s) AND is_active = TRUE
                ORDER BY sort_order ASC NULLS LAST, variant_id ASC
                """.formatted(placeholders);

        Map<Long, List<Map<String, Object>>> byProduct = new HashMap<>();
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < ids.size(); i++) {
                ps.setLong(i + 1, ids.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long productId = rs.getLong("product_id");
                    Map<String, Object> v = new LinkedHashMap<>();
                    v.put("variantId", rs.getLong("variant_id"));
                    v.put("sku", rs.getString("sku"));
                    v.put("variantName", rs.getString("variant_name"));
                    BigDecimal price = rs.getBigDecimal("price");
                    v.put("price", price);
                    v.put("compareAtPrice", rs.getBigDecimal("compare_at_price"));
                    int stock = rs.getInt("stock_quantity");
                    v.put("stockQuantity", stock);
                    v.put("inStock", stock > 0);
                    byProduct.computeIfAbsent(productId, k -> new ArrayList<>()).add(v);
                }
            }
        }
        return byProduct;
    }

    private Map<Long, List<Map<String, Object>>> loadImages(List<Long> ids) throws SQLException {
        String placeholders = ids.stream().map(i -> "?").collect(Collectors.joining(","));
        String sql = """
                SELECT image_id, product_id, variant_id, image_url, alt_text, sort_order, is_primary
                FROM product_images
                WHERE product_id IN (%s)
                ORDER BY is_primary DESC, sort_order ASC NULLS LAST, image_id ASC
                """.formatted(placeholders);

        Map<Long, List<Map<String, Object>>> byProduct = new HashMap<>();
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < ids.size(); i++) {
                ps.setLong(i + 1, ids.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long productId = rs.getLong("product_id");
                    Map<String, Object> img = new LinkedHashMap<>();
                    img.put("imageId", rs.getLong("image_id"));
                    long variantId = rs.getLong("variant_id");
                    img.put("variantId", rs.wasNull() ? null : variantId);
                    img.put("imageUrl", rs.getString("image_url"));
                    img.put("altText", rs.getString("alt_text"));
                    img.put("sortOrder", rs.getInt("sort_order"));
                    img.put("isPrimary", rs.getBoolean("is_primary"));
                    byProduct.computeIfAbsent(productId, k -> new ArrayList<>()).add(img);
                }
            }
        }
        return byProduct;
    }

    private String pickPrimaryImageUrl(List<Map<String, Object>> images) {
        if (images == null || images.isEmpty()) {
            return null;
        }
        for (Map<String, Object> img : images) {
            Object primary = img.get("isPrimary");
            if (primary instanceof Boolean b && b) {
                return (String) img.get("imageUrl");
            }
        }
        // Already ordered: is_primary DESC, sort_order ASC — first row is best fallback
        return (String) images.get(0).get("imageUrl");
    }

    private void applyMinVariantPricing(Map<String, Object> product, List<Map<String, Object>> variants) {
        if (variants == null || variants.isEmpty()) {
            product.put("price", null);
            product.put("compareAtPrice", null);
            return;
        }
        Map<String, Object> cheapest = null;
        BigDecimal minPrice = null;
        for (Map<String, Object> v : variants) {
            BigDecimal price = (BigDecimal) v.get("price");
            if (price == null) {
                continue;
            }
            if (minPrice == null || price.compareTo(minPrice) < 0) {
                minPrice = price;
                cheapest = v;
            }
        }
        if (cheapest == null) {
            product.put("price", null);
            product.put("compareAtPrice", null);
            return;
        }
        product.put("price", cheapest.get("price"));
        product.put("compareAtPrice", cheapest.get("compareAtPrice"));
    }

    /**
     * Admin product list (active + inactive), with all variants (active + inactive).
     */
    public Map<String, Object> listAdminCatalog(Integer categoryId, String q, boolean includeInactive,
                                                int page, int size) throws SQLException {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (!includeInactive) {
            where.append(" AND p.is_active = TRUE");
        }
        if (categoryId != null) {
            where.append(" AND p.category_id = ?");
            params.add(categoryId);
        }
        if (q != null && !q.isBlank()) {
            where.append(" AND (LOWER(p.product_name) LIKE ? OR LOWER(p.slug) LIKE ? OR LOWER(p.brand) LIKE ?)");
            String like = "%" + q.trim().toLowerCase() + "%";
            params.add(like);
            params.add(like);
            params.add(like);
        }

        String countSql = "SELECT COUNT(*) FROM products p" + where;
        int total;
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(countSql)) {
            bindAdminParams(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                total = rs.getInt(1);
            }
        }

        String dataSql = PRODUCT_SELECT + where
                + " ORDER BY p.sort_order ASC NULLS LAST, p.product_id ASC LIMIT ? OFFSET ?";
        List<Map<String, Object>> products = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(dataSql)) {
            bindAdminParams(ps, params);
            ps.setInt(params.size() + 1, size);
            ps.setInt(params.size() + 2, (page - 1) * size);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = mapProductRow(rs);
                    products.add(row);
                    ids.add((Long) row.get("productId"));
                }
            }
        }

        attachAdminVariantsAndImages(products, ids);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", products);
        result.put("page", page);
        result.put("size", size);
        result.put("total", total);
        return result;
    }

    public Map<String, Object> getAdminById(long productId) throws SQLException {
        String sql = PRODUCT_SELECT + " WHERE p.product_id = ?";
        Map<String, Object> product;
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, productId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                product = mapProductRow(rs);
            }
        }
        attachAdminVariantsAndImages(List.of(product), List.of(productId));
        return product;
    }

    /** Update variant stock + low_stock_threshold. Returns updated variant map or null. */
    public Map<String, Object> updateVariantStock(long variantId, int stockQuantity, int lowStockThreshold)
            throws SQLException {
        String sql = """
                UPDATE product_variants
                SET stock_quantity = ?,
                    low_stock_threshold = ?,
                    updated_at = now()
                WHERE variant_id = ?
                RETURNING variant_id, product_id, sku, variant_name, price, compare_at_price,
                          stock_quantity, low_stock_threshold, is_active, sort_order
                """;
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, stockQuantity);
            ps.setInt(2, lowStockThreshold);
            ps.setLong(3, variantId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return mapAdminVariant(rs);
            }
        }
    }

    private void attachAdminVariantsAndImages(List<Map<String, Object>> products, List<Long> ids)
            throws SQLException {
        if (products.isEmpty() || ids.isEmpty()) {
            return;
        }
        Map<Long, List<Map<String, Object>>> variantsByProduct = loadAdminVariants(ids);
        Map<Long, List<Map<String, Object>>> imagesByProduct = loadImages(ids);
        for (Map<String, Object> product : products) {
            Long productId = (Long) product.get("productId");
            List<Map<String, Object>> variants = variantsByProduct.getOrDefault(productId, List.of());
            List<Map<String, Object>> images = imagesByProduct.getOrDefault(productId, List.of());
            product.put("variants", variants);
            product.put("images", images);
            product.put("imageUrl", pickPrimaryImageUrl(images));
            applyMinVariantPricing(product, variants.stream()
                    .filter(v -> Boolean.TRUE.equals(v.get("isActive")))
                    .toList());
        }
    }

    private Map<Long, List<Map<String, Object>>> loadAdminVariants(List<Long> ids) throws SQLException {
        String placeholders = ids.stream().map(i -> "?").collect(Collectors.joining(","));
        String sql = """
                SELECT variant_id, product_id, sku, variant_name, price, compare_at_price,
                       stock_quantity, low_stock_threshold, is_active, sort_order
                FROM product_variants
                WHERE product_id IN (%s)
                ORDER BY sort_order ASC NULLS LAST, variant_id ASC
                """.formatted(placeholders);
        Map<Long, List<Map<String, Object>>> byProduct = new HashMap<>();
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < ids.size(); i++) {
                ps.setLong(i + 1, ids.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long productId = rs.getLong("product_id");
                    byProduct.computeIfAbsent(productId, k -> new ArrayList<>()).add(mapAdminVariant(rs));
                }
            }
        }
        return byProduct;
    }

    private Map<String, Object> mapAdminVariant(ResultSet rs) throws SQLException {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("variantId", rs.getLong("variant_id"));
        v.put("productId", rs.getLong("product_id"));
        v.put("sku", rs.getString("sku"));
        v.put("variantName", rs.getString("variant_name"));
        v.put("price", rs.getBigDecimal("price"));
        v.put("compareAtPrice", rs.getBigDecimal("compare_at_price"));
        int stock = rs.getInt("stock_quantity");
        v.put("stockQuantity", stock);
        v.put("lowStockThreshold", rs.getInt("low_stock_threshold"));
        v.put("isActive", rs.getBoolean("is_active"));
        v.put("inStock", stock > 0);
        return v;
    }

    private void bindAdminParams(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            Object value = params.get(i);
            if (value instanceof Integer n) {
                ps.setInt(i + 1, n);
            } else {
                ps.setString(i + 1, String.valueOf(value));
            }
        }
    }

    // ----- Legacy VendoryX callers (not migrated yet; keep compile-safe) -----

    public Map<String, Object> listCatalog(Integer categoryId, String q, int page, int size) throws SQLException {
        throw notMigrated("listCatalog");
    }

    public Map<String, Object> listAdmin(Integer categoryId, String q, boolean includeInactive,
                                         int page, int size) throws SQLException {
        return listAdminCatalog(categoryId, q, includeInactive, page, size);
    }

    public Product getById(int productId, boolean includeStockDetails) throws SQLException {
        throw notMigrated("getById");
    }

    public boolean skuExists(String sku, Integer excludeProductId) throws SQLException {
        throw notMigrated("skuExists");
    }

    public Product create(Product product) throws SQLException {
        throw notMigrated("create");
    }

    public Product update(Product product) throws SQLException {
        throw notMigrated("update");
    }

    public boolean updateStock(int productId, int stockQuantity, int lowStockThreshold) throws SQLException {
        throw notMigrated("updateStock");
    }

    /** @deprecated Use {@link #getActivePublicById(long)} */
    @Deprecated
    public Product getActiveById(int productId) throws SQLException {
        throw notMigrated("getActiveById(Product) — use getActivePublicById");
    }

    public Product getActiveById(Connection conn, int productId) throws SQLException {
        throw notMigrated("getActiveById(conn)");
    }

    public BigDecimal getActiveUnitPrice(Connection conn, int productId) throws SQLException {
        String sql = """
                SELECT MIN(price) FROM product_variants
                WHERE product_id = ? AND is_active = TRUE
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, productId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getBigDecimal(1);
                }
            }
        }
        return null;
    }

    public String getProductName(Connection conn, int productId) throws SQLException {
        String sql = "SELECT product_name FROM products WHERE product_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, productId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("product_name");
                }
            }
        }
        return "product#" + productId;
    }

    public int getStock(Connection conn, int productId) throws SQLException {
        String sql = """
                SELECT COALESCE(SUM(stock_quantity), 0) FROM product_variants
                WHERE product_id = ? AND is_active = TRUE
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, productId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        }
        return 0;
    }

    private static SQLException notMigrated(String method) {
        return new SQLException("ProductDAO." + method + " not migrated to VickyVirsa schema yet");
    }
}
