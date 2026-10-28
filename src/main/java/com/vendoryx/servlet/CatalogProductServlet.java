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

/** GET /api/catalog/products — active products for any logged-in user. */
public class CatalogProductServlet extends HttpServlet {

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
            Integer categoryId = JsonUtil.queryInt(request, "categoryId");
            String q = JsonUtil.queryText(request, "q");
            int page = JsonUtil.pageOrDefault(request, 1);
            int size = JsonUtil.sizeOrDefault(request, 20, 100);
            JsonUtil.writeSuccess(response, "Products loaded",
                    productDAO.listCatalog(categoryId, q, page, size));
        } catch (SQLException e) {
            e.printStackTrace();
            JsonUtil.writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Database error: " + e.getMessage());
        }
    }
}
