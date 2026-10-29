package com.vendoryx.dao;

import com.vendoryx.config.DBConnection;
import com.vendoryx.model.Category;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class CategoryDAO {

    private static final String SELECT = """
            SELECT category_id, slug, category_name, description, image_url, sort_order, is_active
            FROM categories
            """;

    public List<Category> listActive() throws SQLException {
        return queryList(SELECT + " WHERE is_active = TRUE ORDER BY sort_order ASC, category_name ASC");
    }

    public List<Category> listAll() throws SQLException {
        return queryList(SELECT + " ORDER BY sort_order ASC, category_name ASC");
    }

    public Category getById(int categoryId) throws SQLException {
        String sql = SELECT + " WHERE category_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, categoryId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return map(rs);
                }
            }
        }
        return null;
    }

    public boolean slugExists(String slug, Integer excludeId) throws SQLException {
        String sql = excludeId == null
                ? "SELECT 1 FROM categories WHERE slug = ? LIMIT 1"
                : "SELECT 1 FROM categories WHERE slug = ? AND category_id <> ? LIMIT 1";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, slug);
            if (excludeId != null) {
                ps.setInt(2, excludeId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public String generateUniqueSlug(String name) throws SQLException {
        String base = ProductDAO.slugify(name);
        String candidate = base;
        int n = 2;
        while (slugExists(candidate, null)) {
            candidate = base + "-" + n;
            n++;
            if (n > 10_000) {
                throw new SQLException("Unable to generate unique category slug");
            }
        }
        return candidate;
    }

    public Category create(Category category) throws SQLException {
        String name = category.getCategoryName();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("categoryName is required");
        }
        String slug = category.getSlug();
        if (slug == null || slug.isBlank()) {
            slug = generateUniqueSlug(name);
        } else {
            slug = ProductDAO.slugify(slug);
            if (slugExists(slug, null)) {
                slug = generateUniqueSlug(name);
            }
        }
        String sql = """
                INSERT INTO categories (slug, category_name, description, image_url, sort_order, is_active)
                VALUES (?, ?, ?, ?, ?, ?)
                RETURNING category_id
                """;
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, slug);
            ps.setString(2, name.trim());
            ps.setString(3, category.getDescription());
            ps.setString(4, category.getImageUrl());
            ps.setInt(5, category.getSortOrder());
            ps.setBoolean(6, category.isActive());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return getById(rs.getInt(1));
            }
        }
    }

    public Category update(Category category) throws SQLException {
        Category existing = getById(category.getCategoryId());
        if (existing == null) {
            return null;
        }
        String name = category.getCategoryName() != null ? category.getCategoryName() : existing.getCategoryName();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("categoryName is required");
        }
        String slug;
        if (category.getSlug() != null && !category.getSlug().isBlank()) {
            slug = ProductDAO.slugify(category.getSlug());
            if (slugExists(slug, category.getCategoryId())) {
                slug = generateUniqueSlug(name);
            }
        } else {
            slug = existing.getSlug();
        }
        String sql = """
                UPDATE categories
                SET slug = ?, category_name = ?, description = ?, image_url = ?,
                    sort_order = ?, is_active = ?, updated_at = now()
                WHERE category_id = ?
                """;
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, slug);
            ps.setString(2, name.trim());
            ps.setString(3, category.getDescription() != null ? category.getDescription() : existing.getDescription());
            ps.setString(4, category.getImageUrl() != null ? category.getImageUrl() : existing.getImageUrl());
            ps.setInt(5, category.getSortOrder());
            ps.setBoolean(6, category.isActive());
            ps.setInt(7, category.getCategoryId());
            if (ps.executeUpdate() == 0) {
                return null;
            }
        }
        return getById(category.getCategoryId());
    }

    private List<Category> queryList(String sql) throws SQLException {
        List<Category> list = new ArrayList<>();
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(map(rs));
            }
        }
        return list;
    }

    private Category map(ResultSet rs) throws SQLException {
        Category c = new Category();
        c.setCategoryId(rs.getInt("category_id"));
        c.setSlug(rs.getString("slug"));
        c.setCategoryName(rs.getString("category_name"));
        c.setDescription(rs.getString("description"));
        c.setImageUrl(rs.getString("image_url"));
        c.setSortOrder(rs.getInt("sort_order"));
        c.setActive(rs.getBoolean("is_active"));
        return c;
    }
}