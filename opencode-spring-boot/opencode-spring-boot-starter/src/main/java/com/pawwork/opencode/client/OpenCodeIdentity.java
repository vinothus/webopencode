package com.pawwork.opencode.client;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Mints the OpenCode CLI identity headers.
 *
 * <p>Ported from {@code ChatFragment.openCodeSessionId()/openCodeRequestId()} in the Android
 * reference client, which documents the gateway's constraints:</p>
 * <ul>
 *   <li>{@code x-opencode-session} — required since 2026-09-05 ({@code 400 MissingSessionID}
 *       without it). Since 2026-09-17 only ids shaped like the ones the CLI mints are served the
 *       free tier: {@code ses_} + 26 hex characters. The caller's stable id is therefore hashed
 *       (SHA-256) into that shape — same seed in, same id out.</li>
 *   <li>{@code x-opencode-request} — one id per turn, {@code msg_} + 12 hex + 14 alphanumeric.</li>
 * </ul>
 */
public class OpenCodeIdentity {

    private final String stableId;

    public OpenCodeIdentity(String stableId) {
        this.stableId = (stableId == null || stableId.isBlank())
                ? "ses_paw_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24)
                : stableId.trim();
    }

    /** The seed the gateway session id is derived from (safe to persist). */
    public String stableId() {
        return stableId;
    }

    /**
     * Session id for {@code x-opencode-session}: {@code ses_} + the first 26 hex characters of
     * {@code SHA-256(stableId)}. Deterministic, so the same seed always yields the same id.
     */
    public String sessionId() {
        return "ses_" + sha256Hex(stableId).substring(0, 26);
    }

    /** Per-turn request id for {@code x-opencode-request}: {@code msg_} + 12 hex + 14 alphanumerics. */
    public String requestId() {
        String hex = UUID.randomUUID().toString().replace("-", "");
        StringBuilder alnum = new StringBuilder(14);
        String suffix = UUID.randomUUID().toString().replace("-", "");
        for (int i = 0; i < suffix.length() && alnum.length() < 14; i++) {
            char c = suffix.charAt(i);
            // Keep it strictly [a-z0-9] — digits are swapped for letters to mirror the CLI's shape.
            if (Character.isDigit(c)) {
                alnum.append((char) ('a' + (c - '0')));
            } else if (c >= 'a' && c <= 'f') {
                alnum.append(c);
            }
        }
        while (alnum.length() < 14) {
            alnum.append((char) ('a' + (Math.abs(UUID.randomUUID().getLeastSignificantBits()) % 26)));
        }
        return "msg_" + hex.substring(0, 12) + alnum;
    }

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
            // SHA-256 is mandated by every JRE; this can only fire on a broken runtime.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
