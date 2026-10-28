package com.vendoryx.servlet;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.vendoryx.dao.OrderDAO;
import com.vendoryx.model.Order;
import com.vendoryx.service.BusinessException;
import com.vendoryx.service.OrderService;
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
import java.util.AbstractMap;
import java.util.List;
import java.util.Map;

/** /api/orders — SHOP_USER place + list own orders */
public class OrderServlet extends HttpServlet {

    private final OrderService orderService = new OrderService();
    private final OrderDAO orderDAO = new OrderDAO();

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        CorsUtil.apply(req, resp);
        resp.setStatus(HttpServletResponse.SC_OK);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        CorsUtil.apply(request, response);
        if (!RoleGuard.requireRole(request, response, "SHOP_USER")) {
            return;
        }

        try {
            Integer userId = RoleGuard.getUserId(request);
            List<Integer> shopIds = RoleGuard.getShopIds(request);
            JsonObject body = JsonUtil.parseObject(JsonUtil.readBody(request));
            Integer shopId = JsonUtil.integer(body, "shopId");
            String notes = JsonUtil.text(body, "notes");

            if (shopId == null || shopId <= 0 || body == null || !body.has("items") || !body.get("items").isJsonArray()) {
                JsonUtil.writeError(response, HttpServletResponse.SC_BAD_REQUEST,
                        "shopId and items[] are required");
                return;
            }

            JsonArray itemsArr = body.getAsJsonArray("items");
            List<Map.Entry<Integer, Integer>> items = new ArrayList<>();
            for (JsonElement el : itemsArr) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject item = el.getAsJsonObject();
                Integer productId = JsonUtil.integer(item, "productId");
                Integer quantity = JsonUtil.integer(item, "quantity");
                items.add(new AbstractMap.SimpleEntry<>(productId, quantity));
            }

            Map<String, Object> created = orderService.placeOrder(userId, shopIds, shopId, items, notes);
            JsonUtil.writeSuccess(response, "Order placed", created);
        } catch (BusinessException e) {
            JsonUtil.writeError(response, e.getHttpStatus(), e.getMessage());
        } catch (JsonSyntaxException e) {
            JsonUtil.writeError(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid JSON body");
        } catch (SQLException e) {
            e.printStackTrace();
            JsonUtil.writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Database error: " + e.getMessage());
        }
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
            Integer id = JsonUtil.queryInt(request, "id");
            if (id != null) {
                Order order = orderDAO.getDetail(id);
                if (order == null || !shopIds.contains(order.getShopId())) {
                    JsonUtil.writeError(response, HttpServletResponse.SC_NOT_FOUND, "Order not found");
                    return;
                }
                JsonUtil.writeSuccess(response, "Order loaded", order);
                return;
            }

            Integer shopId = JsonUtil.queryInt(request, "shopId");
            if (shopId != null && !shopIds.contains(shopId)) {
                JsonUtil.writeError(response, HttpServletResponse.SC_FORBIDDEN, "Shop not allowed");
                return;
            }
            int page = JsonUtil.pageOrDefault(request, 1);
            int size = JsonUtil.sizeOrDefault(request, 20, 100);
            JsonUtil.writeSuccess(response, "Orders loaded",
                    orderDAO.listForShops(shopIds, shopId, page, size));
        } catch (SQLException e) {
            e.printStackTrace();
            JsonUtil.writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Database error: " + e.getMessage());
        }
    }
}
