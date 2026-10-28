package com.vendoryx.servlet;

import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.vendoryx.dao.AdminUserDAO;
import com.vendoryx.model.AdminUser;
import com.vendoryx.util.CorsUtil;
import com.vendoryx.util.JsonUtil;
import com.vendoryx.util.JwtUtil;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * POST /api/auth/login — admin_users (username or email + password).
 * Body: { "username": "..." } or { "email": "..." }, "password": "..."
 */
public class LoginServlet extends HttpServlet {

    private final AdminUserDAO adminUserDAO = new AdminUserDAO();

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
            String login = JsonUtil.textAny(body, "username", "email");
            String password = JsonUtil.text(body, "password");

            if (login == null || login.isBlank() || password == null || password.isBlank()) {
                JsonUtil.writeError(response, HttpServletResponse.SC_BAD_REQUEST,
                        "username/email and password are required");
                return;
            }

            AdminUser admin = adminUserDAO.authenticate(login, password);
            if (admin == null) {
                JsonUtil.writeError(response, HttpServletResponse.SC_UNAUTHORIZED,
                        "Invalid username or password");
                return;
            }

            adminUserDAO.updateLastLogin(admin.getAdminId());
            String token = JwtUtil.createAdminToken(admin.getAdminId(), admin.getRole());

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("adminId", admin.getAdminId());
            data.put("username", admin.getUsername());
            data.put("email", admin.getEmail());
            data.put("fullName", admin.getFullName());
            data.put("role", admin.getRole());
            data.put("token", token);

            JsonUtil.writeSuccess(response, "Login successful", data);

        } catch (JsonSyntaxException e) {
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
}
