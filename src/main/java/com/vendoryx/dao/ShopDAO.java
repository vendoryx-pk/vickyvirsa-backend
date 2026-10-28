package com.vendoryx.dao;

import com.vendoryx.config.DBConnection;
import com.vendoryx.model.City;
import com.vendoryx.model.Shop;
import com.vendoryx.model.VisitDay;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

public class ShopDAO {

    private static final String SHOP_SELECT = """
            SELECT s.shop_id, s.shop_code, s.shop_name, s.contact_person, s.phone, s.cnic, s.address,
                   s.latitude, s.longitude, s.city_id, c.city_name, s.day_id, d.day_name,
                   s.status, s.created_at
            FROM shops s
            JOIN cities c ON s.city_id = c.city_id
            JOIN visit_days d ON s.day_id = d.day_id
            """;

    public List<City> getAllCities() throws SQLException {
        String sql = "SELECT city_id, city_name FROM cities ORDER BY city_name";
        List<City> cities = new ArrayList<>();

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                cities.add(new City(rs.getInt("city_id"), rs.getString("city_name")));
            }
        }
        return cities;
    }

    public List<VisitDay> getAllVisitDays() throws SQLException {
        String sql = "SELECT day_id, day_name, day_order FROM visit_days ORDER BY day_order";
        List<VisitDay> days = new ArrayList<>();

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                days.add(new VisitDay(
                        rs.getInt("day_id"),
                        rs.getString("day_name"),
                        rs.getInt("day_order")
                ));
            }
        }
        return days;
    }

    public List<Shop> getAllShops(Integer cityId, Integer dayId) throws SQLException {
        StringBuilder sql = new StringBuilder(SHOP_SELECT);
        sql.append(" WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (cityId != null) {
            sql.append(" AND s.city_id = ?");
            params.add(cityId);
        }
        if (dayId != null) {
            sql.append(" AND s.day_id = ?");
            params.add(dayId);
        }
        sql.append(" ORDER BY s.shop_name");

        List<Shop> shops = new ArrayList<>();

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {

            for (int i = 0; i < params.size(); i++) {
                ps.setInt(i + 1, (Integer) params.get(i));
            }

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    shops.add(mapShop(rs));
                }
            }
        }
        return shops;
    }

    public Shop getShopById(int shopId) throws SQLException {
        String sql = SHOP_SELECT + " WHERE s.shop_id = ?";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, shopId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapShop(rs);
                }
            }
        }
        return null;
    }

    public List<Shop> getShopsByIds(List<Integer> shopIds) throws SQLException {
        List<Shop> shops = new ArrayList<>();
        if (shopIds == null || shopIds.isEmpty()) {
            return shops;
        }

        StringBuilder sql = new StringBuilder(SHOP_SELECT);
        sql.append(" WHERE s.shop_id IN (");
        for (int i = 0; i < shopIds.size(); i++) {
            sql.append(i == 0 ? "?" : ",?");
        }
        sql.append(") ORDER BY s.shop_name");

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {

            for (int i = 0; i < shopIds.size(); i++) {
                ps.setInt(i + 1, shopIds.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    shops.add(mapShop(rs));
                }
            }
        }
        return shops;
    }

    public boolean addShop(Shop shop) throws SQLException {
        String sql = """
                INSERT INTO shops (
                    shop_code, shop_name, contact_person, phone, cnic, address,
                    latitude, longitude, city_id, day_id, status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            bindShopWriteParams(ps, shop);
            return ps.executeUpdate() > 0;
        }
    }

    public boolean updateShop(Shop shop) throws SQLException {
        String sql = """
                UPDATE shops SET
                    shop_code = ?, shop_name = ?, contact_person = ?, phone = ?, cnic = ?, address = ?,
                    latitude = ?, longitude = ?, city_id = ?, day_id = ?, status = ?
                WHERE shop_id = ?
                """;

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            bindShopWriteParams(ps, shop);
            ps.setInt(12, shop.getShopId());
            return ps.executeUpdate() > 0;
        }
    }

    public boolean deleteShop(int shopId) throws SQLException {
        String sql = "DELETE FROM shops WHERE shop_id = ?";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, shopId);
            return ps.executeUpdate() > 0;
        }
    }

    private void bindShopWriteParams(PreparedStatement ps, Shop shop) throws SQLException {
        ps.setString(1, shop.getShopCode());
        ps.setString(2, shop.getShopName());
        ps.setString(3, shop.getContactPerson());
        ps.setString(4, shop.getPhone());
        ps.setString(5, shop.getCnic());
        ps.setString(6, shop.getAddress());
        setNullableDouble(ps, 7, shop.getLatitude());
        setNullableDouble(ps, 8, shop.getLongitude());
        ps.setInt(9, shop.getCityId());
        ps.setInt(10, shop.getDayId());
        ps.setString(11, shop.getStatus() != null && !shop.getStatus().isBlank()
                ? shop.getStatus()
                : "ACTIVE");
    }

    private void setNullableDouble(PreparedStatement ps, int index, Double value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.DOUBLE);
        } else {
            ps.setDouble(index, value);
        }
    }

    private Shop mapShop(ResultSet rs) throws SQLException {
        Shop shop = new Shop();
        shop.setShopId(rs.getInt("shop_id"));
        shop.setShopCode(rs.getString("shop_code"));
        shop.setShopName(rs.getString("shop_name"));
        shop.setContactPerson(rs.getString("contact_person"));
        shop.setPhone(rs.getString("phone"));
        shop.setCnic(rs.getString("cnic"));
        shop.setAddress(rs.getString("address"));

        double lat = rs.getDouble("latitude");
        shop.setLatitude(rs.wasNull() ? null : lat);
        double lng = rs.getDouble("longitude");
        shop.setLongitude(rs.wasNull() ? null : lng);

        shop.setCityId(rs.getInt("city_id"));
        shop.setCityName(rs.getString("city_name"));
        shop.setDayId(rs.getInt("day_id"));
        shop.setDayName(rs.getString("day_name"));
        shop.setStatus(rs.getString("status"));
        shop.setCreatedAt(rs.getTimestamp("created_at"));
        return shop;
    }
}
