package com.vendoryx.servlet;

import com.vendoryx.dao.ShopDAO;
import com.vendoryx.model.Shop;
import com.vendoryx.util.CorsUtil;
import com.vendoryx.util.JsonUtil;
import com.vendoryx.util.RoleGuard;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GET /api/my-shops — SHOP_USER only.
 * Returns shops from JWT shopIds (joined with cities + visit_days).
 */
public class MyShopsServlet extends HttpServlet {

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
        if (!RoleGuard.requireRole(request, response, "SHOP_USER")) {
            return;
        }

        try {
            List<Integer> shopIds = RoleGuard.getShopIds(request);
            List<Shop> shops = shopDAO.getShopsByIds(shopIds);

            List<Map<String, Object>> data = new ArrayList<>();
            for (Shop shop : shops) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("shopId", shop.getShopId());
                row.put("shopCode", shop.getShopCode());
                row.put("shopName", shop.getShopName());
                row.put("contactPerson", shop.getContactPerson());
                row.put("phone", shop.getPhone());
                row.put("address", shop.getAddress());
                row.put("cityName", shop.getCityName());
                row.put("dayName", shop.getDayName());
                row.put("status", shop.getStatus());
                data.add(row);
            }

            JsonUtil.writeSuccess(response, "My shops loaded", data);
        } catch (SQLException e) {
            e.printStackTrace();
            JsonUtil.writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Database error: " + e.getMessage());
        }
    }
}
