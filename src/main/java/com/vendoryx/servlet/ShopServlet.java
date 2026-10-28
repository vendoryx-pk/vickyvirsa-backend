package com.vendoryx.servlet;

import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
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
import java.util.List;

/**
 * /api/shops
 * GET    ?id=        → single shop
 * GET    ?cityId=&dayId= → filtered list
 * GET                → all shops
 * POST               → create
 * PUT                → update
 * DELETE ?id=        → delete
 */
public class ShopServlet extends HttpServlet {

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
        if (!RoleGuard.requireRole(request, response, "ADMIN", "DEVELOPER")) {
            return;
        }

        try {
            Integer id = JsonUtil.queryInt(request, "id");
            if (id != null) {
                Shop shop = shopDAO.getShopById(id);
                if (shop == null) {
                    JsonUtil.writeError(response, HttpServletResponse.SC_NOT_FOUND, "Shop not found");
                    return;
                }
                JsonUtil.writeSuccess(response, "Shop loaded", shop);
                return;
            }

            Integer cityId = JsonUtil.queryInt(request, "cityId");
            Integer dayId = JsonUtil.queryInt(request, "dayId");
            List<Shop> shops = shopDAO.getAllShops(cityId, dayId);
            JsonUtil.writeSuccess(response, "Shops loaded", shops);

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

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        CorsUtil.apply(request, response);
        if (!RoleGuard.requireRole(request, response, "ADMIN", "DEVELOPER")) {
            return;
        }

        try {
            Shop shop = parseShop(JsonUtil.readBody(request), false);
            if (shop == null) {
                JsonUtil.writeError(response, HttpServletResponse.SC_BAD_REQUEST,
                        "Invalid shop payload. Required: shopCode, shopName, cityId, dayId");
                return;
            }

            boolean created = shopDAO.addShop(shop);
            if (!created) {
                JsonUtil.writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                        "Shop create not success");
                return;
            }

            JsonUtil.writeSuccess(response, "Shop created successfully", shop);

        } catch (JsonSyntaxException e) {
            e.printStackTrace();
            JsonUtil.writeError(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid JSON body");
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

    @Override
    protected void doPut(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        CorsUtil.apply(request, response);
        if (!RoleGuard.requireRole(request, response, "ADMIN", "DEVELOPER")) {
            return;
        }

        try {
            Shop shop = parseShop(JsonUtil.readBody(request), true);
            if (shop == null || shop.getShopId() <= 0) {
                JsonUtil.writeError(response, HttpServletResponse.SC_BAD_REQUEST,
                        "Invalid shop payload. Required: shopId, shopCode, shopName, cityId, dayId");
                return;
            }

            boolean updated = shopDAO.updateShop(shop);
            if (!updated) {
                JsonUtil.writeError(response, HttpServletResponse.SC_NOT_FOUND,
                        "Shop update not success — shop not found");
                return;
            }

            Shop refreshed = shopDAO.getShopById(shop.getShopId());
            JsonUtil.writeSuccess(response, "Shop updated successfully", refreshed);

        } catch (JsonSyntaxException e) {
            e.printStackTrace();
            JsonUtil.writeError(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid JSON body");
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

    @Override
    protected void doDelete(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        CorsUtil.apply(request, response);
        if (!RoleGuard.requireRole(request, response, "ADMIN", "DEVELOPER")) {
            return;
        }

        try {
            Integer id = JsonUtil.queryInt(request, "id");
            if (id == null || id <= 0) {
                JsonUtil.writeError(response, HttpServletResponse.SC_BAD_REQUEST, "id query param is required");
                return;
            }

            boolean deleted = shopDAO.deleteShop(id);
            if (!deleted) {
                JsonUtil.writeError(response, HttpServletResponse.SC_NOT_FOUND,
                        "Shop delete not success — shop not found");
                return;
            }

            JsonUtil.writeSuccess(response, "Shop deleted successfully", null);

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

    private Shop parseShop(String rawBody, boolean requireId) {
        JsonObject body = JsonUtil.parseObject(rawBody);
        if (body == null) {
            return null;
        }

        Integer shopId = JsonUtil.integer(body, "shopId");
        String shopCode = JsonUtil.textAny(body, "shopCode", "shop_code");
        String shopName = JsonUtil.textAny(body, "shopName", "shop_name");
        Integer cityId = JsonUtil.integer(body, "cityId");
        if (cityId == null) {
            cityId = JsonUtil.integer(body, "city_id");
        }
        Integer dayId = JsonUtil.integer(body, "dayId");
        if (dayId == null) {
            dayId = JsonUtil.integer(body, "day_id");
        }

        if (shopCode == null || shopCode.isBlank()
                || shopName == null || shopName.isBlank()
                || cityId == null || cityId <= 0
                || dayId == null || dayId <= 0) {
            return null;
        }

        if (requireId && (shopId == null || shopId <= 0)) {
            return null;
        }

        Shop shop = new Shop();
        if (shopId != null) {
            shop.setShopId(shopId);
        }
        shop.setShopCode(shopCode);
        shop.setShopName(shopName);
        shop.setContactPerson(JsonUtil.textAny(body, "contactPerson", "contact_person"));
        shop.setPhone(JsonUtil.text(body, "phone"));
        shop.setCnic(JsonUtil.textAny(body, "cnic", "idCard", "id_card", "nic"));
        shop.setAddress(JsonUtil.text(body, "address"));
        shop.setLatitude(JsonUtil.decimal(body, "latitude"));
        shop.setLongitude(JsonUtil.decimal(body, "longitude"));
        shop.setCityId(cityId);
        shop.setDayId(dayId);
        shop.setStatus(JsonUtil.text(body, "status"));
        return shop;
    }
}
