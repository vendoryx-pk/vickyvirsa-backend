package com.vendoryx.util;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.vendoryx.config.Env;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

public final class JwtUtil {

    private static final Algorithm ALGORITHM;
    private static final long EXPIRY_HOURS = 12;

    static {
        String secret = Env.first("VENDORYX_JWT_SECRET", "JWT_SECRET");
        if (secret == null || secret.isBlank()) {
            throw new ExceptionInInitializerError(
                    "Missing required environment variable: VENDORYX_JWT_SECRET "
                            + "(must be at least 32 characters).");
        }
        secret = secret.trim();
        if (secret.length() < 32) {
            throw new ExceptionInInitializerError(
                    "VENDORYX_JWT_SECRET must be at least 32 characters (got " + secret.length() + ").");
        }
        ALGORITHM = Algorithm.HMAC256(secret);
    }

    private JwtUtil() {}

    public static String createAdminToken(int adminId, String role) {
        return JWT.create()
                .withClaim("adminId", adminId)
                .withClaim("role", role != null ? role : "")
                .withIssuedAt(new Date())
                .withExpiresAt(Date.from(Instant.now().plus(EXPIRY_HOURS, ChronoUnit.HOURS)))
                .sign(ALGORITHM);
    }

    public static DecodedJWT verify(String token) throws JWTVerificationException {
        return JWT.require(ALGORITHM).build().verify(token);
    }

    public static Integer getAdminId(DecodedJWT jwt) {
        if (jwt.getClaim("adminId").isNull()) {
            return null;
        }
        return jwt.getClaim("adminId").asInt();
    }

    public static String getRole(DecodedJWT jwt) {
        return jwt.getClaim("role").asString();
    }
}
