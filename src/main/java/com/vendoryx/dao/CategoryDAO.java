package com.vendoryx.dao;

import com.vendoryx.config.DBConnection;
import com.vendoryx.model.Category;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class CategoryDAO {

    public List<Category> listActive() throws SQLException {
        String sql = """
                SELECT category_id, category_name, description, is_active
                FROM categories
                WHERE is_active = TRUE
                ORDER BY category_name
                """;
        return queryList(sql, null);
    }

    public List<Category> listAll() throws SQLException {
        String sql = """
                SELECT category_id, category_name, description, is_active
                FROM categories
                ORDER BY category_name
                """;
        return queryList(sql, null);
    }

    public Category getById(int categoryId) throws SQLException {
        String sql = """
                SELECT category_id, category_name, description, is_active
                FROM categories WHERE category_id = ?
                """;
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

    public Category create(Category category) throws SQLException {
        String sql = """
                INSERT INTO categories (category_name, description, is_active)
                VALUES (?, ?, ?)
                """;
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, category.getCategoryName());
            ps.setString(2, category.getDescription());
            ps.setBoolean(3, category.isActive());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return getById(keys.getInt(1));
                }
            }
        }
        return null;
    }

    public Category update(Category category) throws SQLException {
        String sql = """
                UPDATE categories
                SET category_name = ?, description = ?, is_active = ?
                WHERE category_id = ?
                """;
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, category.getCategoryName());
            ps.setString(2, category.getDescription());
            ps.setBoolean(3, category.isActive());
            ps.setInt(4, category.getCategoryId());
            if (ps.executeUpdate() == 0) {
                return null;
            }
        }
        return getById(category.getCategoryId());
    }

    private List<Category> queryList(String sql, Integer unused) throws SQLException {
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
        c.setCategoryName(rs.getString("category_name"));
        c.setDescription(rs.getString("description"));
        c.setActive(rs.getBoolean("is_active"));
        return c;
    }
}
