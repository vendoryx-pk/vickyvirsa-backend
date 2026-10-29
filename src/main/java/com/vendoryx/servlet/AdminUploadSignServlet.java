package com.vendoryx.servlet;

import com.vendoryx.dao.AdminAuditDAO;
import com.vendoryx.util.CloudinarySignUtil;
import com.vendoryx.util.CorsUtil;
import com.vendoryx.util.JsonUtil;
import com.vendoryx.util.RoleGuard;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Map;

/** POST /api/admin/uploads/sign — Cloudinary signed upload params (no secret in response). */
public class AdminUploadSignServlet extends HttpServlet {

    private final AdminAuditDAO auditDAO = new AdminAuditDAO();

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        CorsUtil.apply(req, resp);
        resp.setStatus(HttpServletResponse.SC_OK);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        CorsUtil.apply(request, response);
        if (!RoleGuard.requireAdmin(request, response)) {
            return;
        }
        try {
            Map<String, Object> signed = CloudinarySignUtil.signProductUpload();
            try {
                auditDAO.log(RoleGuard.getAdminId(request), "UPLOAD_SIGN", "cloudinary", "products",
                        Map.of("folder", signed.get("folder"), "timestamp", signed.get("timestamp")));
            } catch (SQLException ignored) {
                // signing still succeeds if audit write fails
            }
            JsonUtil.writeSuccess(response, "Upload signature created", signed);
        } catch (IllegalStateException e) {
            JsonUtil.writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, e.getMessage());
        }
    }
}