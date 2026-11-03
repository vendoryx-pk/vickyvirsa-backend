package com.vendoryx.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.vendoryx.config.Env;
import com.vendoryx.dao.EmailLogDAO;
import com.vendoryx.dao.GuestCheckoutDAO.CheckoutVariant;
import com.vendoryx.dao.GuestCheckoutDAO.CreatedOrder;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Async owner email on new guest order (Resend HTTP API). Never fails the order.
 */
public class OwnerOrderAlertService {

    private static final OwnerOrderAlertService INSTANCE = new OwnerOrderAlertService();

    private final EmailLogDAO emailLogDAO = new EmailLogDAO();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ExecutorService executor = Executors.newFixedThreadPool(2, new ThreadFactory() {
        private final AtomicInteger n = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "owner-order-alert-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    });

    public static OwnerOrderAlertService getInstance() {
        return INSTANCE;
    }

    public record LineItem(String productName, String variantName, int quantity, BigDecimal unitPrice) {}

    public void notifyNewGuestOrderAsync(CreatedOrder order, String customerName, String phone,
                                         String address, String city,
                                         List<CheckoutVariant> variants, List<Integer> quantities) {
        List<LineItem> lines = new ArrayList<>();
        for (int i = 0; i < variants.size(); i++) {
            CheckoutVariant v = variants.get(i);
            int qty = quantities.get(i);
            String name = v.productName();
            if (v.variantName() != null && !v.variantName().isBlank()) {
                name = name + " (" + v.variantName() + ")";
            }
            lines.add(new LineItem(name, v.variantName(), qty, v.price()));
        }
        executor.execute(() -> {
            try {
                sendOwnerAlert(order, customerName, phone, address, city, lines);
            } catch (Exception e) {
                System.err.println("Owner order alert failed (order not affected): " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    private void sendOwnerAlert(CreatedOrder order, String customerName, String phone,
                                String address, String city, List<LineItem> lines) throws Exception {
        String apiKey = Env.first("RESEND_API_KEY");
        String from = Env.first("MAIL_FROM");
        String to = Env.first("OWNER_NOTIFY_EMAIL");
        String adminUrl = Env.first("ADMIN_URL");

        String subject = "Naya order " + order.orderNumber() + " (Rs " + formatMoney(order.totalAmount()) + ")";
        String toEmail = (to != null && !to.isBlank()) ? to.trim() : "unconfigured@invalid";

        long emailId = emailLogDAO.insertPending(
                order.orderId(), EmailLogDAO.TYPE_ORDER_ALERT_OWNER, toEmail, subject);

        if (apiKey == null || apiKey.isBlank() || from == null || from.isBlank()
                || to == null || to.isBlank()) {
            String msg = "Email skipped: missing RESEND_API_KEY, MAIL_FROM, or OWNER_NOTIFY_EMAIL";
            System.out.println(msg);
            emailLogDAO.markFailed(emailId, msg);
            return;
        }

        String text = buildTextBody(order, customerName, phone, address, city, lines, adminUrl);
        String html = buildHtmlBody(order, customerName, phone, address, city, lines, adminUrl);

        JsonObject payload = new JsonObject();
        payload.addProperty("from", from.trim());
        payload.add("to", com.vendoryx.util.JsonUtil.gson().toJsonTree(List.of(to.trim())));
        payload.addProperty("subject", subject);
        payload.addProperty("text", text);
        payload.addProperty("html", html);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.resend.com/emails"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + apiKey.trim())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        int code = response.statusCode();
        String body = response.body() != null ? response.body() : "";

        if (code >= 200 && code < 300) {
            String messageId = null;
            try {
                JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                if (json.has("id") && !json.get("id").isJsonNull()) {
                    messageId = json.get("id").getAsString();
                }
            } catch (Exception ignored) {
                // keep messageId null
            }
            emailLogDAO.markSent(emailId, messageId);
            System.out.println("Owner order alert SENT order=" + order.orderNumber()
                    + " emailId=" + emailId + " providerId=" + messageId);
        } else {
            String err = "Resend HTTP " + code + ": " + body;
            emailLogDAO.markFailed(emailId, err);
            System.err.println("Owner order alert FAILED order=" + order.orderNumber() + " " + err);
        }
    }

    private static String buildTextBody(CreatedOrder order, String customerName, String phone,
                                        String address, String city, List<LineItem> lines,
                                        String adminUrl) {
        StringBuilder sb = new StringBuilder();
        sb.append("Naya COD order mila.\n\n");
        sb.append("Order: ").append(nullToEmpty(order.orderNumber())).append('\n');
        sb.append("Customer: ").append(nullToEmpty(customerName)).append('\n');
        sb.append("Phone: ").append(nullToEmpty(phone)).append('\n');
        sb.append("City: ").append(nullToEmpty(city)).append('\n');
        sb.append("Address: ").append(nullToEmpty(address)).append("\n\n");
        sb.append("Items:\n");
        for (LineItem line : lines) {
            sb.append("- ").append(nullToEmpty(line.productName()))
                    .append(" x").append(line.quantity())
                    .append(" @ Rs ").append(formatMoney(line.unitPrice()))
                    .append('\n');
        }
        sb.append('\n');
        sb.append("Subtotal: Rs ").append(formatMoney(order.subtotal())).append('\n');
        sb.append("Shipping: Rs ").append(formatMoney(order.shippingFee())).append('\n');
        sb.append("Total: Rs ").append(formatMoney(order.totalAmount())).append('\n');
        sb.append("Payment: COD\n");
        if (adminUrl != null && !adminUrl.isBlank()) {
            sb.append("\nAdmin panel: ").append(adminUrl.trim()).append('\n');
            sb.append("Order id: ").append(order.orderId()).append('\n');
        }
        return sb.toString();
    }

    private static String buildHtmlBody(CreatedOrder order, String customerName, String phone,
                                        String address, String city, List<LineItem> lines,
                                        String adminUrl) {
        StringBuilder items = new StringBuilder();
        for (LineItem line : lines) {
            items.append("<tr>")
                    .append("<td>").append(esc(line.productName())).append("</td>")
                    .append("<td style=\"text-align:center\">").append(line.quantity()).append("</td>")
                    .append("<td style=\"text-align:right\">Rs ").append(esc(formatMoney(line.unitPrice())))
                    .append("</td>")
                    .append("</tr>");
        }
        String adminLink = "";
        if (adminUrl != null && !adminUrl.isBlank()) {
            String href = adminUrl.trim();
            adminLink = "<p><a href=\"" + esc(href) + "\">Admin panel</a>"
                    + " (order id " + order.orderId() + ")</p>";
        }
        return """
                <div style="font-family:sans-serif;font-size:14px;color:#222">
                  <h2>Naya COD order</h2>
                  <p><strong>Order:</strong> %s</p>
                  <p><strong>Customer:</strong> %s<br/>
                     <strong>Phone:</strong> %s<br/>
                     <strong>City:</strong> %s<br/>
                     <strong>Address:</strong> %s</p>
                  <table cellpadding="6" cellspacing="0" border="1" style="border-collapse:collapse">
                    <thead><tr><th>Item</th><th>Qty</th><th>Price</th></tr></thead>
                    <tbody>%s</tbody>
                  </table>
                  <p>
                    Subtotal: <strong>Rs %s</strong><br/>
                    Shipping: <strong>Rs %s</strong><br/>
                    Total: <strong>Rs %s</strong><br/>
                    Payment: <strong>COD</strong>
                  </p>
                  %s
                </div>
                """.formatted(
                esc(order.orderNumber()),
                esc(customerName),
                esc(phone),
                esc(city),
                esc(address),
                items,
                esc(formatMoney(order.subtotal())),
                esc(formatMoney(order.shippingFee())),
                esc(formatMoney(order.totalAmount())),
                adminLink
        );
    }

    static String esc(String raw) {
        if (raw == null) {
            return "";
        }
        return raw
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String formatMoney(BigDecimal value) {
        if (value == null) {
            return "0";
        }
        return value.stripTrailingZeros().toPlainString();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
