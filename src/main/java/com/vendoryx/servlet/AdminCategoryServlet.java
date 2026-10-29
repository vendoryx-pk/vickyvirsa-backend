package com.vendoryx.servlet;

import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.vendoryx.dao.AdminAuditDAO;
import com.vendoryx.dao.CategoryDAO;
import com.vendoryx.model.Category;
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

/** /api/admin/categories — OWNER/STAFF list + create/update */
public class AdminCategoryServlet extends HttpServlet {

    private final CategoryDAO categoryDAO = new CategoryDAO();
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
            Integer id = JsonUtil.queryInt(request, "id");
            if (id != null) {
                Category category = categoryDAO.getById(id);
                if (category == null) {
                    JsonUtil.writeError(response, HttpServletResponse.SC_NOT_FOUND, "Category not found");
                    return;
                }
                JsonUtil.writeSuccess(response, "Category loaded", category);
                return;
            }
            JsonUtil.writeSuccess(response, "Categories loaded", categoryDAO.listAll());
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
        if (!RoleGuard.requireAdmin(request, response)) {
            return;
        }
        try {
            Category category = parseCategory(JsonUtil.readBody(request), false);
            if (category == null) {
                JsonUtil.writeError(response, HttpServletResponse.SC_BAD_REQUEST, "categoryName is required");
                return;
            }
            Category created = categoryDAO.create(category);
            auditDAO.log(RoleGuard.getAdminId(request), "CATEGORY_CREATE", "category",
                    created.getCategoryId(), Map.of("slug", created.getSlug(), "categoryName", created.getCategoryName()));
            JsonUtil.writeSuccess(response, "Category created", created);
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
    protected void doPut(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        CorsUtil.apply(request, response);
        if (!RoleGuard.requireAdmin(request, response)) {
            return;
        }
        try {
            Integer id = JsonUtil.queryInt(request, "id");
            Category category = parseCategory(JsonUtil.readBody(request), true);
            if (id == null || id <= 0 || category == null) {
                JsonUtil.writeError(response, HttpServletResponse.SC_BAD_REQUEST,
                        "id query param and categoryName are required");
                return;
            }
            category.setCategoryId(id);
            Category updated = categoryDAO.update(category);
            if (updated == null) {
                JsonUtil.writeError(response, HttpServletResponse.SC_NOT_FOUND, "Category not found");
                return;
            }
            auditDAO.log(RoleGuard.getAdminId(request), "CATEGORY_UPDATE", "category", id,
                    Map.of("slug", updated.getSlug(), "categoryName", updated.getCategoryName()));
            JsonUtil.writeSuccess(response, "Category updated", updated);
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

    private Category parseCategory(String raw, boolean forUpdate) {
        JsonObject body = JsonUtil.parseObject(raw);
        String name = JsonUtil.textAny(body, "categoryName", "category_name", "name");
        if (name == null || name.isBlank()) {
            return null;
        }
        Category c = new Category();
        c.setCategoryName(name);
        c.setSlug(JsonUtil.textAny(body, "slug"));
        c.setDescription(JsonUtil.text(body, "description"));
        c.setImageUrl(JsonUtil.textAny(body, "imageUrl", "image_url"));
        Integer sort = JsonUtil.integer(body, "sortOrder");
        if (sort == null) {
            sort = JsonUtil.integer(body, "sort_order");
        }
        c.setSortOrder(sort != null ? sort : 0);
        Boolean active = JsonUtil.bool(body, "active");
        if (active == null) {
            active = JsonUtil.bool(body, "isActive");
        }
        c.setActive(active != null ? active : true);
        return c;
    }
}