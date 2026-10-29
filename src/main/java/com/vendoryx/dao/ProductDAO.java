package com.vendoryx.dao;

import com.google.gson.JsonSyntaxException;
import com.vendoryx.config.DBConnection;
import com.vendoryx.util.JsonUtil;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * VickyVirsa B2C product catalog (products + variants + images) against Neon schema.
 */
public class ProductDAO {

    private static final String PRODUCT_SELECT = """
            SELECT p.product_id, p.slug, p.product_name, p.short_description, p.description,
                   p.brand, p.specs::text AS specs_json, p.is_active, p.is_featured, p.sort_order,
                   p.meta_title, p.meta_description,
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
        m.put("metaTitle", rs.getString("meta_title"));
        m.put("metaDescription", rs.getString("meta_description"));
        m.put("isFeatured", rs.getBoolean("is_featured"));
        m.put("isActive", rs.getBoolean("is_active"));
        m.put("sortOrder", rs.getInt("sort_order"));

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

    /** Specs jsonb for DB (NOT NULL — empty object when absent). Meta columns are separate. */
    public static String buildSpecsJson(Object specs) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (specs instanceof Map<?, ?> raw) {
            for (Map.Entry<?, ?> e : raw.entrySet()) {
                map.put(String.valueOf(e.getKey()), e.getValue());
            }
        } else if (specs instanceof String s && !s.isBlank()) {
            try {
                Object parsed = JsonUtil.gson().fromJson(s, Object.class);
                if (parsed instanceof Map<?, ?> raw) {
                    for (Map.Entry<?, ?> e : raw.entrySet()) {
                        map.put(String.valueOf(e.getKey()), e.getValue());
                    }
                }
            } catch (JsonSyntaxException ignored) {
                // empty object
            }
        }
        return JsonUtil.gson().toJson(map);
    }

    /** @deprecated meta lives in columns; use {@link #buildSpecsJson(Object)} */
    @Deprecated
    public static String buildSpecsJson(Object specs, String metaTitle, String metaDescription) {
        return buildSpecsJson(specs);
    }

    public static String buildAttributesJson(Object attributes) {
        return buildSpecsJson(attributes);
    }

    public static String slugify(String name) {
        if (name == null || name.isBlank()) {
            return "product";
        }
        String slug = name.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return slug.isBlank() ? "product" : slug;
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
     * Admin product list with all variants (active + inactive).
     * {@code activeFilter}: null = all, true = active only, false = inactive only.
     */
    public Map<String, Object> listAdminCatalog(Integer categoryId, String q, Boolean activeFilter,
                                                int page, int size) throws SQLException {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (activeFilter != null) {
            where.append(activeFilter ? " AND p.is_active = TRUE" : " AND p.is_active = FALSE");
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
                RETURNING variant_id, product_id, sku, variant_name, attributes::text AS attributes_json,
                          price, compare_at_price, stock_quantity, low_stock_threshold, is_active, sort_order
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
                SELECT variant_id, product_id, sku, variant_name, attributes::text AS attributes_json,
                       price, compare_at_price, stock_quantity, low_stock_threshold, is_active, sort_order
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
        v.put("attributes", parseSpecs(rs.getString("attributes_json")));
        v.put("price", rs.getBigDecimal("price"));
        v.put("compareAtPrice", rs.getBigDecimal("compare_at_price"));
        int stock = rs.getInt("stock_quantity");
        v.put("stockQuantity", stock);
        v.put("lowStockThreshold", rs.getInt("low_stock_threshold"));
        v.put("isActive", rs.getBoolean("is_active"));
        v.put("inStock", stock > 0);
        v.put("sortOrder", rs.getInt("sort_order"));
        return v;
    }

    private Map<String, Object> mapImage(ResultSet rs) throws SQLException {
        Map<String, Object> img = new LinkedHashMap<>();
        img.put("imageId", rs.getLong("image_id"));
        long variantId = rs.getLong("variant_id");
        img.put("variantId", rs.wasNull() ? null : variantId);
        img.put("imageUrl", rs.getString("image_url"));
        img.put("altText", rs.getString("alt_text"));
        int sortOrder = rs.getInt("sort_order");
        img.put("sortOrder", rs.wasNull() ? null : sortOrder);
        img.put("isPrimary", rs.getBoolean("is_primary"));
        img.put("productId", rs.getLong("product_id"));
        return img;
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

    // ----- Admin product / variant / image CRUD -----

    public boolean slugExists(String slug, Long excludeProductId) throws SQLException {
        String sql = excludeProductId == null
                ? "SELECT 1 FROM products WHERE slug = ? LIMIT 1"
                : "SELECT 1 FROM products WHERE slug = ? AND product_id <> ? LIMIT 1";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, slug);
            if (excludeProductId != null) {
                ps.setLong(2, excludeProductId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public String generateUniqueSlug(String productName) throws SQLException {
        return generateUniqueSlug(productName, null);
    }

    public String generateUniqueSlug(String productName, Long excludeProductId) throws SQLException {
        String base = slugify(productName);
        String candidate = base;
        int n = 2;
        while (slugExists(candidate, excludeProductId)) {
            candidate = base + "-" + n;
            n++;
            if (n > 10_000) {
                throw new SQLException("Unable to generate unique slug for: " + base);
            }
        }
        return candidate;
    }

    /**
     * Create product with optional nested variants/images in one transaction.
     * {@code fields} keys: productName, slug?, shortDescription?, description?, brand?,
     * specs?, metaTitle?, metaDescription?, isFeatured?, isActive?, sortOrder?, categoryId,
     * variants? (List&lt;Map&gt;), images? (List&lt;Map&gt;).
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> createAdminProduct(Map<String, Object> fields) throws SQLException {
        String productName = str(fields.get("productName"));
        if (productName == null || productName.isBlank()) {
            throw new IllegalArgumentException("productName is required");
        }
        Integer categoryId = intVal(fields.get("categoryId"));
        if (categoryId == null || categoryId <= 0) {
            throw new IllegalArgumentException("categoryId is required");
        }

        List<Map<String, Object>> variants = castMapList(fields.get("variants"));
        if (variants != null) {
        java.util.HashSet<String> skus = new java.util.HashSet<>();
        for (Map<String, Object> v : variants) {
            validateVariantFields(v, null, true);
            String sku = str(v.get("sku"));
            if (sku != null && !skus.add(sku.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Duplicate sku in request: " + sku);
            }
        }
        }
        List<Map<String, Object>> images = castMapList(fields.get("images"));

        String slug = str(fields.get("slug"));
        if (slug == null || slug.isBlank()) {
            slug = generateUniqueSlug(productName);
        } else {
            slug = slugify(slug);
            if (slugExists(slug, null)) {
                slug = generateUniqueSlug(productName);
            }
        }

        String specsJson = buildSpecsJson(fields.get("specs"));
        boolean isFeatured = boolOr(fields.get("isFeatured"), false);
        boolean isActive = boolOr(fields.get("isActive"), true);
        Integer sortOrder = intVal(fields.get("sortOrder"));
        if (sortOrder == null) {
            sortOrder = 0;
        }

        long productId;
        try (Connection conn = DBConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                productId = insertProduct(conn, slug, productName,
                        str(fields.get("shortDescription")),
                        str(fields.get("description")),
                        str(fields.get("brand")),
                        specsJson,
                        str(fields.get("metaTitle")),
                        str(fields.get("metaDescription")),
                        isActive, isFeatured, sortOrder, categoryId);

                if (variants != null) {
                    for (Map<String, Object> v : variants) {
                        insertVariant(conn, productId, v);
                    }
                }
                if (images != null) {
                    for (Map<String, Object> img : images) {
                        insertImage(conn, productId, img);
                    }
                }
                conn.commit();
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
        return getAdminById(productId);
    }

    public Map<String, Object> updateAdminProduct(long productId, Map<String, Object> fields,
                                                   List<Map<String, Object>> nestedVariants)
            throws SQLException {
        Map<String, Object> existing = getAdminById(productId);
        if (existing == null) {
            return null;
        }

        String productName = fields.containsKey("productName")
                ? str(fields.get("productName"))
                : (String) existing.get("productName");
        if (productName == null || productName.isBlank()) {
            throw new IllegalArgumentException("productName is required");
        }

        Integer categoryId = fields.containsKey("categoryId")
                ? intVal(fields.get("categoryId"))
                : categoryIdFrom(existing);
        if (categoryId == null || categoryId <= 0) {
            throw new IllegalArgumentException("categoryId is required");
        }

        String slug;
        if (fields.containsKey("slug")) {
            String raw = str(fields.get("slug"));
            if (raw == null || raw.isBlank()) {
                slug = generateUniqueSlug(productName, productId);
            } else {
                slug = slugify(raw);
                if (slugExists(slug, productId)) {
                    slug = generateUniqueSlug(slug, productId);
                }
            }
        } else {
            slug = (String) existing.get("slug");
        }

        Object specsSource = fields.containsKey("specs") ? fields.get("specs") : existing.get("specs");
        String specsJson = buildSpecsJson(specsSource);
        String metaTitle = fields.containsKey("metaTitle")
                ? str(fields.get("metaTitle"))
                : str(existing.get("metaTitle"));
        String metaDescription = fields.containsKey("metaDescription")
                ? str(fields.get("metaDescription"))
                : str(existing.get("metaDescription"));

        boolean isFeatured = fields.containsKey("isFeatured")
                ? boolOr(fields.get("isFeatured"), false)
                : Boolean.TRUE.equals(existing.get("isFeatured"));
        boolean isActive = fields.containsKey("isActive")
                ? boolOr(fields.get("isActive"), true)
                : Boolean.TRUE.equals(existing.get("isActive"));
        Integer sortOrder = fields.containsKey("sortOrder")
                ? intVal(fields.get("sortOrder"))
                : intVal(existing.get("sortOrder"));
        if (sortOrder == null) {
            sortOrder = 0;
        }

        String shortDescription = fields.containsKey("shortDescription")
                ? str(fields.get("shortDescription"))
                : (String) existing.get("shortDescription");
        String description = fields.containsKey("description")
                ? str(fields.get("description"))
                : (String) existing.get("description");
        String brand = fields.containsKey("brand")
                ? str(fields.get("brand"))
                : (String) existing.get("brand");

        if (nestedVariants != null) {
            for (Map<String, Object> v : nestedVariants) {
                Long variantId = longVal(v.get("variantId"));
                validateVariantFields(v, variantId, variantId == null || variantId <= 0);
            }
        }

        try (Connection conn = DBConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                String sql = """
                        UPDATE products
                        SET slug = ?, product_name = ?, short_description = ?, description = ?,
                            brand = ?, specs = ?::jsonb, meta_title = ?, meta_description = ?,
                            is_active = ?, is_featured = ?, sort_order = ?, category_id = ?,
                            updated_at = now()
                        WHERE product_id = ?
                        """;
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, slug);
                    ps.setString(2, productName);
                    ps.setString(3, shortDescription);
                    ps.setString(4, description);
                    ps.setString(5, brand);
                    setJsonb(ps, 6, specsJson);
                    ps.setString(7, metaTitle);
                    ps.setString(8, metaDescription);
                    ps.setBoolean(9, isActive);
                    ps.setBoolean(10, isFeatured);
                    ps.setInt(11, sortOrder);
                    ps.setInt(12, categoryId);
                    ps.setLong(13, productId);
                    if (ps.executeUpdate() == 0) {
                        conn.rollback();
                        return null;
                    }
                }

                if (nestedVariants != null) {
                    for (Map<String, Object> v : nestedVariants) {
                        Long variantId = longVal(v.get("variantId"));
                        if (variantId == null || variantId <= 0) {
                            insertVariant(conn, productId, v);
                        } else {
                            updateVariantOnConn(conn, variantId, v, true);
                        }
                    }
                }
                conn.commit();
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
        return getAdminById(productId);
    }

    public Map<String, Object> softDeleteProduct(long productId) throws SQLException {
        String sql = "UPDATE products SET is_active = FALSE, updated_at = now() WHERE product_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, productId);
            if (ps.executeUpdate() == 0) {
                return null;
            }
        }
        return getAdminById(productId);
    }

    public Map<String, Object> getVariantById(long variantId) throws SQLException {
        String sql = """
                SELECT variant_id, product_id, sku, variant_name, attributes::text AS attributes_json,
                       price, compare_at_price, stock_quantity, low_stock_threshold, is_active, sort_order
                FROM product_variants WHERE variant_id = ?
                """;
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, variantId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return mapAdminVariant(rs);
            }
        }
    }

    public Map<String, Object> createVariant(Map<String, Object> fields) throws SQLException {
        Long productId = longVal(fields.get("productId"));
        if (productId == null || productId <= 0) {
            throw new IllegalArgumentException("productId is required");
        }
        if (getAdminById(productId) == null) {
            return null;
        }
        validateVariantFields(fields, null, true);
        try (Connection conn = DBConnection.getConnection()) {
            long variantId = insertVariant(conn, productId, fields);
            return getVariantById(variantId);
        }
    }

    public Map<String, Object> updateVariant(long variantId, Map<String, Object> fields,
                                              boolean allowPriceChange) throws SQLException {
        validateVariantFields(fields, variantId, false);
        try (Connection conn = DBConnection.getConnection()) {
            if (!updateVariantOnConn(conn, variantId, fields, allowPriceChange)) {
                return null;
            }
        }
        return getVariantById(variantId);
    }

    public Map<String, Object> softDeleteVariant(long variantId) throws SQLException {
        String sql = """
                UPDATE product_variants SET is_active = FALSE, updated_at = now()
                WHERE variant_id = ?
                RETURNING variant_id, product_id, sku, variant_name, attributes::text AS attributes_json,
                          price, compare_at_price, stock_quantity, low_stock_threshold, is_active, sort_order
                """;
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, variantId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return mapAdminVariant(rs);
            }
        }
    }

    public Map<String, Object> getImageById(long imageId) throws SQLException {
        String sql = """
                SELECT image_id, product_id, variant_id, image_url, alt_text, sort_order, is_primary
                FROM product_images WHERE image_id = ?
                """;
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, imageId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return mapImage(rs);
            }
        }
    }

    public Map<String, Object> createImage(Map<String, Object> fields) throws SQLException {
        Long productId = longVal(fields.get("productId"));
        if (productId == null || productId <= 0) {
            throw new IllegalArgumentException("productId is required");
        }
        String imageUrl = str(fields.get("imageUrl"));
        if (imageUrl == null || imageUrl.isBlank()) {
            throw new IllegalArgumentException("imageUrl is required");
        }
        try (Connection conn = DBConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                long imageId = insertImage(conn, productId, fields);
                conn.commit();
                return getImageById(imageId);
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    public Map<String, Object> updateImage(long imageId, Map<String, Object> fields) throws SQLException {
        Map<String, Object> existing = getImageById(imageId);
        if (existing == null) {
            return null;
        }
        long productId = ((Number) existing.get("productId")).longValue();
        String imageUrl = fields.containsKey("imageUrl")
                ? str(fields.get("imageUrl"))
                : (String) existing.get("imageUrl");
        if (imageUrl == null || imageUrl.isBlank()) {
            throw new IllegalArgumentException("imageUrl is required");
        }
        String altText = fields.containsKey("altText")
                ? str(fields.get("altText"))
                : (String) existing.get("altText");
        Integer sortOrder = fields.containsKey("sortOrder")
                ? intVal(fields.get("sortOrder"))
                : intVal(existing.get("sortOrder"));
        if (sortOrder == null) {
            sortOrder = 0;
        }
        boolean isPrimary = fields.containsKey("isPrimary")
                ? boolOr(fields.get("isPrimary"), false)
                : Boolean.TRUE.equals(existing.get("isPrimary"));
        Long variantId = fields.containsKey("variantId")
                ? longVal(fields.get("variantId"))
                : longVal(existing.get("variantId"));

        try (Connection conn = DBConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                if (isPrimary) {
                    clearPrimaryFlags(conn, productId);
                }
                String sql = """
                        UPDATE product_images
                        SET image_url = ?, alt_text = ?, sort_order = ?, is_primary = ?, variant_id = ?
                        WHERE image_id = ?
                        """;
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, imageUrl);
                    ps.setString(2, altText);
                    ps.setInt(3, sortOrder);
                    ps.setBoolean(4, isPrimary);
                    if (variantId == null) {
                        ps.setNull(5, Types.BIGINT);
                    } else {
                        ps.setLong(5, variantId);
                    }
                    ps.setLong(6, imageId);
                    ps.executeUpdate();
                }
                conn.commit();
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
        return getImageById(imageId);
    }

    public boolean deleteImage(long imageId) throws SQLException {
        String sql = "DELETE FROM product_images WHERE image_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, imageId);
            return ps.executeUpdate() > 0;
        }
    }

    public void clearPrimaryFlags(Connection conn, long productId) throws SQLException {
        String sql = "UPDATE product_images SET is_primary = FALSE WHERE product_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, productId);
            ps.executeUpdate();
        }
    }

    private long insertProduct(Connection conn, String slug, String productName,
                               String shortDescription, String description, String brand,
                               String specsJson, String metaTitle, String metaDescription,
                               boolean isActive, boolean isFeatured,
                               int sortOrder, int categoryId) throws SQLException {
        String sql = """
                INSERT INTO products (
                    slug, product_name, short_description, description, brand, specs,
                    meta_title, meta_description, is_active, is_featured, sort_order, category_id
                ) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?)
                RETURNING product_id
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, slug);
            ps.setString(2, productName);
            ps.setString(3, shortDescription);
            ps.setString(4, description);
            ps.setString(5, brand);
            setJsonb(ps, 6, specsJson != null ? specsJson : "{}");
            ps.setString(7, metaTitle);
            ps.setString(8, metaDescription);
            ps.setBoolean(9, isActive);
            ps.setBoolean(10, isFeatured);
            ps.setInt(11, sortOrder);
            ps.setInt(12, categoryId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("Failed to insert product");
                }
                return rs.getLong(1);
            }
        }
    }

    private long insertVariant(Connection conn, long productId, Map<String, Object> fields)
            throws SQLException {
        BigDecimal price = decimalVal(fields.get("price"));
        Integer stock = intVal(fields.get("stockQuantity"));
        if (stock == null) {
            stock = 0;
        }
        Integer low = intVal(fields.get("lowStockThreshold"));
        if (low == null) {
            low = 5;
        }
        Integer sortOrder = intVal(fields.get("sortOrder"));
        if (sortOrder == null) {
            sortOrder = 0;
        }
        boolean isActive = boolOr(fields.get("isActive"), true);
        String attributesJson = buildAttributesJson(fields.get("attributes"));

        String sql = """
                INSERT INTO product_variants (
                    product_id, sku, variant_name, attributes, price, compare_at_price,
                    stock_quantity, low_stock_threshold, is_active, sort_order
                ) VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?)
                RETURNING variant_id
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, productId);
            ps.setString(2, str(fields.get("sku")));
            ps.setString(3, str(fields.get("variantName")));
            setJsonb(ps, 4, attributesJson);
            ps.setBigDecimal(5, price);
            BigDecimal compare = decimalVal(fields.get("compareAtPrice"));
            if (compare == null) {
                ps.setNull(6, Types.NUMERIC);
            } else {
                ps.setBigDecimal(6, compare);
            }
            ps.setInt(7, stock);
            ps.setInt(8, low);
            ps.setBoolean(9, isActive);
            ps.setInt(10, sortOrder);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("Failed to insert variant");
                }
                return rs.getLong(1);
            }
        }
    }

    private boolean updateVariantOnConn(Connection conn, long variantId, Map<String, Object> fields,
                                         boolean allowPriceChange) throws SQLException {
        Map<String, Object> existing;
        String select = """
                SELECT variant_id, product_id, sku, variant_name, attributes::text AS attributes_json,
                       price, compare_at_price, stock_quantity, low_stock_threshold, is_active, sort_order
                FROM product_variants WHERE variant_id = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(select)) {
            ps.setLong(1, variantId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return false;
                }
                existing = mapAdminVariant(rs);
            }
        }

        String sku = fields.containsKey("sku") ? str(fields.get("sku")) : (String) existing.get("sku");
        String variantName = fields.containsKey("variantName")
                ? str(fields.get("variantName"))
                : (String) existing.get("variantName");
        BigDecimal price;
        BigDecimal compareAt;
        if (allowPriceChange) {
            price = fields.containsKey("price")
                    ? decimalVal(fields.get("price"))
                    : (BigDecimal) existing.get("price");
            compareAt = fields.containsKey("compareAtPrice")
                    ? decimalVal(fields.get("compareAtPrice"))
                    : (BigDecimal) existing.get("compareAtPrice");
        } else {
            price = (BigDecimal) existing.get("price");
            compareAt = (BigDecimal) existing.get("compareAtPrice");
        }
        if (price == null) {
            throw new IllegalArgumentException("variant price is required");
        }
        Integer stock = fields.containsKey("stockQuantity")
                ? intVal(fields.get("stockQuantity"))
                : intVal(existing.get("stockQuantity"));
        if (stock == null) {
            stock = 0;
        }
        Integer low = fields.containsKey("lowStockThreshold")
                ? intVal(fields.get("lowStockThreshold"))
                : intVal(existing.get("lowStockThreshold"));
        if (low == null) {
            low = 5;
        }
        boolean isActive = fields.containsKey("isActive")
                ? boolOr(fields.get("isActive"), true)
                : Boolean.TRUE.equals(existing.get("isActive"));
        Integer sortOrder = fields.containsKey("sortOrder")
                ? intVal(fields.get("sortOrder"))
                : intVal(existing.get("sortOrder"));
        if (sortOrder == null) {
            sortOrder = 0;
        }
        String attributesJson = fields.containsKey("attributes")
                ? buildAttributesJson(fields.get("attributes"))
                : buildAttributesJson(existing.get("attributes"));

        String sql = """
                UPDATE product_variants
                SET sku = ?, variant_name = ?, attributes = ?::jsonb, price = ?, compare_at_price = ?,
                    stock_quantity = ?, low_stock_threshold = ?, is_active = ?,
                    sort_order = ?, updated_at = now()
                WHERE variant_id = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sku);
            ps.setString(2, variantName);
            setJsonb(ps, 3, attributesJson);
            ps.setBigDecimal(4, price);
            if (compareAt == null) {
                ps.setNull(5, Types.NUMERIC);
            } else {
                ps.setBigDecimal(5, compareAt);
            }
            ps.setInt(6, stock);
            ps.setInt(7, low);
            ps.setBoolean(8, isActive);
            ps.setInt(9, sortOrder);
            ps.setLong(10, variantId);
            return ps.executeUpdate() > 0;
        }
    }

    private long insertImage(Connection conn, long productId, Map<String, Object> fields)
            throws SQLException {
        String imageUrl = str(fields.get("imageUrl"));
        if (imageUrl == null || imageUrl.isBlank()) {
            throw new IllegalArgumentException("imageUrl is required");
        }
        Integer sortOrder = intVal(fields.get("sortOrder"));
        if (sortOrder == null) {
            sortOrder = 0;
        }
        boolean isPrimary = boolOr(fields.get("isPrimary"), false);
        Long variantId = longVal(fields.get("variantId"));

        if (isPrimary) {
            clearPrimaryFlags(conn, productId);
        }

        String sql = """
                INSERT INTO product_images (
                    product_id, variant_id, image_url, alt_text, sort_order, is_primary
                ) VALUES (?, ?, ?, ?, ?, ?)
                RETURNING image_id
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, productId);
            if (variantId == null) {
                ps.setNull(2, Types.BIGINT);
            } else {
                ps.setLong(2, variantId);
            }
            ps.setString(3, imageUrl);
            ps.setString(4, str(fields.get("altText")));
            ps.setInt(5, sortOrder);
            ps.setBoolean(6, isPrimary);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("Failed to insert image");
                }
                return rs.getLong(1);
            }
        }
    }

    private static void setJsonb(PreparedStatement ps, int index, String json) throws SQLException {
        // products.specs / product_variants.attributes are NOT NULL (default {})
        ps.setString(index, (json == null || json.isBlank()) ? "{}" : json);
    }

    /**
     * Validate variant payload. On create ({@code requirePrice}) price/sku/name required.
     * App rules: price &gt; 0, stock &gt;= 0, compare_at_price (if set) &gt; price, sku unique.
     */
    public void validateVariantFields(Map<String, Object> fields, Long excludeVariantId, boolean requirePrice)
            throws SQLException {
        if (fields == null) {
            throw new IllegalArgumentException("variant payload is required");
        }
        String sku = str(fields.get("sku"));
        String variantName = str(fields.get("variantName"));
        if (requirePrice) {
            if (sku == null || sku.isBlank()) {
                throw new IllegalArgumentException("sku is required");
            }
            if (variantName == null || variantName.isBlank()) {
                throw new IllegalArgumentException("variantName is required");
            }
        }
        if (sku != null && skuExists(sku, excludeVariantId)) {
            throw new IllegalArgumentException("sku already exists: " + sku);
        }

        if (fields.containsKey("price") || requirePrice) {
            BigDecimal price = decimalVal(fields.get("price"));
            if (price == null) {
                throw new IllegalArgumentException("price is required");
            }
            if (price.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("price must be greater than 0");
            }
            if (fields.containsKey("compareAtPrice")) {
                BigDecimal compare = decimalVal(fields.get("compareAtPrice"));
                if (compare != null && compare.compareTo(price) <= 0) {
                    throw new IllegalArgumentException("compareAtPrice must be greater than price");
                }
            }
        } else if (fields.containsKey("compareAtPrice") && excludeVariantId != null) {
            Map<String, Object> existing = getVariantById(excludeVariantId);
            if (existing != null) {
                BigDecimal price = (BigDecimal) existing.get("price");
                BigDecimal compare = decimalVal(fields.get("compareAtPrice"));
                if (compare != null && price != null && compare.compareTo(price) <= 0) {
                    throw new IllegalArgumentException("compareAtPrice must be greater than price");
                }
            }
        }

        if (fields.containsKey("stockQuantity")) {
            Integer stock = intVal(fields.get("stockQuantity"));
            if (stock == null || stock < 0) {
                throw new IllegalArgumentException("stockQuantity must be >= 0");
            }
        }
        if (fields.containsKey("lowStockThreshold")) {
            Integer low = intVal(fields.get("lowStockThreshold"));
            if (low == null || low < 0) {
                throw new IllegalArgumentException("lowStockThreshold must be >= 0");
            }
        }
    }

    public boolean skuExists(String sku, Long excludeVariantId) throws SQLException {
        if (sku == null || sku.isBlank()) {
            return false;
        }
        String sql = excludeVariantId == null
                ? "SELECT 1 FROM product_variants WHERE sku = ? LIMIT 1"
                : "SELECT 1 FROM product_variants WHERE sku = ? AND variant_id <> ? LIMIT 1";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sku.trim());
            if (excludeVariantId != null) {
                ps.setLong(2, excludeVariantId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castMapList(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof List<?> list) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    out.add((Map<String, Object>) m);
                }
            }
            return out;
        }
        return null;
    }

    private static Integer categoryIdFrom(Map<String, Object> product) {
        Object cat = product.get("category");
        if (cat instanceof Map<?, ?> m) {
            return intVal(m.get("categoryId"));
        }
        return intVal(product.get("categoryId"));
    }

    private static String str(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    private static Integer intVal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            String s = String.valueOf(value).trim();
            if (s.isEmpty()) {
                return null;
            }
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long longVal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            String s = String.valueOf(value).trim();
            if (s.isEmpty()) {
                return null;
            }
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal decimalVal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        if (value instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        try {
            String s = String.valueOf(value).trim();
            if (s.isEmpty()) {
                return null;
            }
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean boolOr(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    /** Legacy listAdmin signature: includeInactive=false → active only; true → all. */
    public Map<String, Object> listAdmin(Integer categoryId, String q, boolean includeInactive,
                                         int page, int size) throws SQLException {
        Boolean activeFilter = includeInactive ? null : Boolean.TRUE;
        return listAdminCatalog(categoryId, q, activeFilter, page, size);
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
}
