package com.stocka.backend.modules.devices.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

import org.springframework.stereotype.Component;

/**
 * Generates and hashes the heartbeat tokens a player presents as {@code Authorization: Device
 * <token>}. A token is {@value #TOKEN_PREFIX} followed by 32 random bytes in unpadded base64url
 * (43 characters); only its sha256 (lowercase hex) and its first {@value #PREFIX_LENGTH} characters
 * are persisted, so a database leak does not expose usable credentials.
 */
@Component
public class DeviceTokenService {
    /** Fixed prefix that makes a heartbeat token recognizable in logs and config files. */
    public static final String TOKEN_PREFIX = "bht_";
    /** Characters of the raw token kept in clear to help users match a token with a device. */
    public static final int PREFIX_LENGTH = 8;

    private static final int RANDOM_BYTES = 32;
    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();
    private static final HexFormat HEX = HexFormat.of();

    private final SecureRandom random = new SecureRandom();

    /**
     * A freshly generated token with its derived, storable parts.
     *
     * @param token  raw token to show the user exactly once
     * @param hash   lowercase sha256 hex of {@code token}
     * @param prefix first {@value #PREFIX_LENGTH} characters of {@code token}
     */
    public record GeneratedToken(String token, String hash, String prefix) { }

    /**
     * Generates a new random token.
     *
     * @return the token and its hash/prefix
     */
    public GeneratedToken generate() {
        byte[] bytes = new byte[RANDOM_BYTES];
        random.nextBytes(bytes);
        String token = TOKEN_PREFIX + BASE64_URL.encodeToString(bytes);
        return new GeneratedToken(token, hash(token), prefixOf(token));
    }

    /**
     * Hashes a raw token the way it is stored.
     *
     * @param token raw token as presented by the player
     * @return lowercase sha256 hex (64 characters)
     */
    public String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HEX.formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible en esta JVM", e);
        }
    }

    /**
     * Clear-text prefix kept next to the hash.
     *
     * @param token raw token
     * @return its first {@value #PREFIX_LENGTH} characters (the whole token when shorter)
     */
    public static String prefixOf(String token) {
        return token.length() <= PREFIX_LENGTH ? token : token.substring(0, PREFIX_LENGTH);
    }
}
