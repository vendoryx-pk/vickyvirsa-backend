package com.vendoryx.servlet;

import com.vendoryx.dao.ProductDAO;
import com.vendoryx.util.CorsUtil;
import com.vendoryx.util.JsonUtil;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * Public VickyVirsa catalog (no auth).
 * GET /api/products — active products with category, images, variants
 * GET /api/products/{id}
 */
public class ProductPublicServlet extends HttpServlet {

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
        try {
            Long id = pathProductId(request);
            if (id != null) {
                Map<String, Object> product = productDAO.getActivePublicById(id);
                if (product == null) {
                    JsonUtil.writeError(response, HttpServletResponse.SC_NOT_FOUND, "Product not found");
                    return;
                }
                JsonUtil.writeSuccess(response, "Product loaded", product);
                return;
            }
            List<Map<String, Object>> items = productDAO.listActivePublic();
            JsonUtil.writeSuccess(response, "Products loaded", items);
        } catch (NumberFormatException e) {
            JsonUtil.writeError(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid product id");
        } catch (SQLException e) {
            e.printStackTrace();
            JsonUtil.writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Database error: " + e.getMessage());
        }
    }

    private Long pathProductId(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath() != null ? request.getContextPath() : "";
        if (uri.startsWith(context)) {
            uri = uri.substring(context.length());
        }
        if ("/api/products".equals(uri) || "/api/products/".equals(uri)) {
            return null;
        }
        if (uri.startsWith("/api/products/")) {
            String rest = uri.substring("/api/products/".length());
            if (rest.isEmpty() || rest.contains("/")) {
                throw new NumberFormatException("bad path");
            }
            return Long.parseLong(rest);
        }
        String pathInfo = request.getPathInfo();
        if (pathInfo == null || pathInfo.isBlank() || "/".equals(pathInfo)) {
            return null;
        }
        String idPart = pathInfo.startsWith("/") ? pathInfo.substring(1) : pathInfo;
        if (idPart.contains("/")) {
            throw new NumberFormatException("bad path");
        }
        return Long.parseLong(idPart);
    }
}
