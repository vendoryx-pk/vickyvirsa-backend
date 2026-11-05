package com.vendoryx.servlet;

import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.vendoryx.dao.OrderTrackDAO;
import com.vendoryx.util.CorsUtil;
import com.vendoryx.util.IpRateLimiter;
import com.vendoryx.util.JsonUtil;
import com.vendoryx.util.PhoneUtil;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.PrintWriter;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * POST /api/orders/track — public order lookup by orderNumber + phone.
 * Body: { "orderNumber": "VV-...", "phone": "03..." }
 */
public class OrderTrackServlet extends HttpServlet {

    private static final IpRateLimiter RATE_LIMITER = new IpRateLimiter(10, 60_000L);

    private final OrderTrackDAO orderTrackDAO = new OrderTrackDAO();

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        CorsUtil.apply(req, resp);
        resp.setStatus(HttpServletResponse.SC_OK);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        CorsUtil.apply(request, response);

        String ip = clientIp(request);
        if (!RATE_LIMITER.tryAcquire(ip)) {
            writeErrorOnly(response, 429, "Too many requests");
            return;
        }

        try {
            JsonObject body = JsonUtil.parseObject(JsonUtil.readBody(request));
            String orderNumber = JsonUtil.textAny(body, "orderNumber", "order_number");
            String phoneRaw = JsonUtil.textAny(body, "phone");

            if (orderNumber == null || orderNumber.isBlank() || phoneRaw == null || phoneRaw.isBlank()) {
                // Same generic 404 — do not reveal which field failed
                writeNotFound(response);
                return;
            }

            String phoneE164 = PhoneUtil.normalizePhoneE164(phoneRaw);
            if (phoneE164 == null) {
                writeNotFound(response);
                return;
            }

            Map<String, Object> track = orderTrackDAO.findPublicTrack(orderNumber, phoneE164);
            if (track == null) {
                writeNotFound(response);
                return;
            }

            JsonUtil.writeSuccess(response, "Order found", track);
        } catch (JsonSyntaxException e) {
            writeNotFound(response);
        } catch (SQLException e) {
            e.printStackTrace();
            JsonUtil.writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Database error: " + e.getMessage());
        }
    }

    private static void writeNotFound(HttpServletResponse response) throws IOException {
        writeErrorOnly(response, HttpServletResponse.SC_NOT_FOUND, "Order not found");
    }

    /** Exact shape: {"error":"..."} */
    private static void writeErrorOnly(HttpServletResponse response, int status, String error)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        PrintWriter out = response.getWriter();
        out.print(JsonUtil.gson().toJson(body));
        out.flush();
    }

    private static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr() != null ? request.getRemoteAddr() : "unknown";
    }
}
