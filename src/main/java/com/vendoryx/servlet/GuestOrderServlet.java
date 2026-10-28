package com.vendoryx.servlet;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.vendoryx.service.BusinessException;
import com.vendoryx.service.OrderService;
import com.vendoryx.util.CorsUtil;
import com.vendoryx.util.JsonUtil;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.sql.SQLException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Public guest checkout (no auth) — VickyVirsa B2C.
 * Body: { customerName, phone, address, city, notes?, items:[{productId, quantity}] }
 */
public class GuestOrderServlet extends HttpServlet {

    private final OrderService orderService = new OrderService();

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        CorsUtil.apply(req, resp);
        resp.setStatus(HttpServletResponse.SC_OK);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        CorsUtil.apply(request, response);
        try {
            JsonObject body = JsonUtil.parseObject(JsonUtil.readBody(request));
            if (body == null) {
                JsonUtil.writeError(response, HttpServletResponse.SC_BAD_REQUEST, "JSON body required");
                return;
            }

            String customerName = JsonUtil.text(body, "customerName");
            String phone = JsonUtil.text(body, "phone");
            String address = JsonUtil.text(body, "address");
            String city = JsonUtil.text(body, "city");
            String notes = JsonUtil.text(body, "notes");

            if (!body.has("items") || !body.get("items").isJsonArray()) {
                JsonUtil.writeError(response, HttpServletResponse.SC_BAD_REQUEST, "items[] is required");
                return;
            }

            JsonArray itemsArr = body.getAsJsonArray("items");
            List<Map.Entry<Integer, Integer>> items = new ArrayList<>();
            for (JsonElement el : itemsArr) {
                if (!el.isJsonObject()) {
                    JsonUtil.writeError(response, HttpServletResponse.SC_BAD_REQUEST,
                            "Each item must be an object with productId and quantity");
                    return;
                }
                JsonObject item = el.getAsJsonObject();
                Integer productId = JsonUtil.integer(item, "productId");
                Integer quantity = JsonUtil.integer(item, "quantity");
                items.add(new AbstractMap.SimpleEntry<>(productId, quantity));
            }

            Map<String, Object> created = orderService.placeGuestOrder(
                    customerName, phone, address, city, notes, items);
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
}
