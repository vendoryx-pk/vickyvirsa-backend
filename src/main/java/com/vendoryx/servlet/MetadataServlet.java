package com.vendoryx.servlet;

import com.vendoryx.dao.ShopDAO;
import com.vendoryx.util.CorsUtil;
import com.vendoryx.util.JsonUtil;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GET /api/metadata
 * Returns cities and visit days for dropdowns / filters.
 */
public class MetadataServlet extends HttpServlet {

    private final ShopDAO shopDAO = new ShopDAO();

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
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("cities", shopDAO.getAllCities());
            data.put("days", shopDAO.getAllVisitDays());
            JsonUtil.writeSuccess(response, "Metadata loaded", data);
        } catch (SQLException e) {
            e.printStackTrace();
            JsonUtil.writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Database error: " + e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            JsonUtil.writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Server error: " + e.getMessage());
        }
    }
}
