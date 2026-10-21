package com.pawwork.opencode.client;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * OpenCodeIdentity that rotates the stable session ID every hour.
 *
 * The stable ID format: <prefix>-<year><month><day><hour> (UTC)
 * e.g., "pawwork-opencode-spring-2026100322"
 *
 * This produces a new x-opencode-session every hour (at the top of the hour, UTC).
 * The gateway will see a new session ID each hour, which resets its rate-limit
 * accounting for that session.
 */
@Component
public class HourlyRotatingOpenCodeIdentity extends OpenCodeIdentity {

    private static final DateTimeFormatter HOUR_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMddHH").withZone(ZoneOffset.UTC);

    private final String prefix;

    public HourlyRotatingOpenCodeIdentity(String prefix) {
        super(computeStableId(prefix));
        this.prefix = prefix;
    }

    private static String computeStableId(String prefix) {
        String hour = DateTimeFormatter.ofPattern("yyyyMMddHH")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        return prefix + "-" + hour;
    }

    @Override
    public String stableId() {
        return computeStableId(prefix);
    }

    @Override
    public String sessionId() {
        return "ses_" + sha256Hex(stableId()).substring(0, 26);
    }

    // Copy of the private sha256Hex method from parent
    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
