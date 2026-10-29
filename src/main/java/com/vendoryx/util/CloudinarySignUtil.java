package com.vendoryx.util;

import com.vendoryx.config.Env;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** Cloudinary signed upload params (secret never returned). */
public final class CloudinarySignUtil {

    private CloudinarySignUtil() {}

    public static Map<String, Object> signProductUpload() {
        String cloudName = Env.first("CLOUDINARY_CLOUD_NAME");
        String apiKey = Env.first("CLOUDINARY_API_KEY");
        String apiSecret = Env.first("CLOUDINARY_API_SECRET");
        if (cloudName == null || apiKey == null || apiSecret == null) {
            throw new IllegalStateException(
                    "Cloudinary not configured (CLOUDINARY_CLOUD_NAME, CLOUDINARY_API_KEY, CLOUDINARY_API_SECRET)");
        }

        long timestamp = System.currentTimeMillis() / 1000L;
        String folder = "products";

        TreeMap<String, String> toSign = new TreeMap<>();
        toSign.put("folder", folder);
        toSign.put("timestamp", String.valueOf(timestamp));

        StringBuilder params = new StringBuilder();
        for (Map.Entry<String, String> e : toSign.entrySet()) {
            if (!params.isEmpty()) {
                params.append('&');
            }
            params.append(e.getKey()).append('=').append(e.getValue());
        }
        String signature = sha1Hex(params + apiSecret);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cloudName", cloudName);
        out.put("apiKey", apiKey);
        out.put("timestamp", timestamp);
        out.put("folder", folder);
        out.put("signature", signature);
        return out;
    }

    private static String sha1Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 not available", e);
        }
    }
}