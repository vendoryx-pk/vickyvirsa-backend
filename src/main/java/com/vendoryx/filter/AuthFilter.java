package com.vendoryx.filter;

import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.vendoryx.util.JwtUtil;
import com.vendoryx.util.JsonUtil;
import com.vendoryx.util.RoleGuard;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Protects /api/* except public catalog / guest checkout / login.
 * /api/admin/* requires JWT with role OWNER or STAFF.
 */
public class AuthFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse resp = (HttpServletResponse) response;

        if ("OPTIONS".equalsIgnoreCase(req.getMethod())) {
            resp.setStatus(HttpServletResponse.SC_NO_CONTENT);
            return;
        }

        String path = req.getRequestURI();
        String context = req.getContextPath() != null ? req.getContextPath() : "";
        if (path.startsWith(context)) {
            path = path.substring(context.length());
        }

        if (isPublic(path, req.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        String authHeader = req.getHeader("Authorization");
        if (authHeader == null || !authHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            writeJson(resp, HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized");
            return;
        }

        String token = authHeader.substring(7).trim();
        if (token.isEmpty()) {
            writeJson(resp, HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized");
            return;
        }

        try {
            DecodedJWT jwt = JwtUtil.verify(token);
            Integer adminId = JwtUtil.getAdminId(jwt);
            String role = JwtUtil.getRole(jwt);
            if (adminId != null) {
                req.setAttribute(RoleGuard.ATTR_ADMIN_ID, adminId);
            }
            req.setAttribute(RoleGuard.ATTR_ROLE, role);

            if (path.startsWith("/api/admin/") && !RoleGuard.isAdminRole(role)) {
                writeJson(resp, HttpServletResponse.SC_FORBIDDEN, "Forbidden");
                return;
            }

            chain.doFilter(request, response);
        } catch (JWTVerificationException e) {
            writeJson(resp, HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized");
        }
    }

    private static boolean isPublic(String path, String method) {
        if ("/api/auth/login".equals(path) || "/health".equals(path)) {
            return true;
        }
        if ("GET".equalsIgnoreCase(method)) {
            if ("/api/products".equals(path)) {
                return true;
            }
            if (path.startsWith("/api/products/")) {
                String rest = path.substring("/api/products/".length());
                return !rest.isEmpty() && rest.indexOf('/') < 0;
            }
        }
        return "POST".equalsIgnoreCase(method) && "/api/orders/guest".equals(path);
    }

    private void writeJson(HttpServletResponse resp, int status, String message) throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("message", message);
        PrintWriter out = resp.getWriter();
        out.print(JsonUtil.gson().toJson(body));
        out.flush();
    }
}
