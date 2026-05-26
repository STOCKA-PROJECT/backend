package com.stocka.backend.modules.auth.twofactor;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

/**
 * RFC 6238 TOTP code generator/verifier. Built on top of HMAC-SHA1 (Java
 * built-in) and an inline RFC 4648 Base32 codec — no third-party TOTP library
 * required.
 *
 * <p>The shared secret is 20 bytes (160 bits) of random data, Base32-encoded
 * for human typability. The TOTP step is the canonical 30 seconds; the
 * verifier accepts a ±1 step drift to be friendly to slow phones.
 */
@Component
public class TotpGenerator {

    /** Standard TOTP step in seconds. */
    public static final int STEP_SECONDS = 30;

    /** Number of digits in the generated code. */
    public static final int DIGITS = 6;

    /** ±1 step drift tolerated by {@link #verify(String, String)}. */
    public static final int VERIFICATION_WINDOW_STEPS = 1;

    private static final int SECRET_BYTES = 20;
    private static final int[] DIGIT_POWERS =
            { 1, 10, 100, 1000, 10000, 100000, 1000000, 10000000 };

    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Generates a new shared secret as Base32 (without padding). Length is
     * 32 characters for a 160-bit secret.
     *
     * @return base32 secret ready to drop in an {@code otpauth://} URI
     */
    public String generateSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        secureRandom.nextBytes(bytes);
        return base32Encode(bytes);
    }

    /**
     * Returns the current TOTP code for the given secret, mostly for tests.
     */
    public String currentCode(String base32Secret) {
        return computeCode(base32Secret, currentStep());
    }

    /**
     * Verifies a code against the secret. Trims whitespace and any non-digit
     * separator the user might paste between groups.
     *
     * @param base32Secret stored secret
     * @param presented user-supplied code
     * @return {@code true} when the code matches any step in
     *         {@code [now-1, now, now+1]}
     */
    public boolean verify(String base32Secret, String presented) {
        if (base32Secret == null || presented == null) return false;
        String normalized = presented.replaceAll("\\s+", "").replace("-", "");
        if (normalized.length() != DIGITS || !normalized.chars().allMatch(Character::isDigit)) {
            return false;
        }
        long step = currentStep();
        for (int delta = -VERIFICATION_WINDOW_STEPS; delta <= VERIFICATION_WINDOW_STEPS; delta++) {
            if (computeCode(base32Secret, step + delta).equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Builds an {@code otpauth://totp/...} URI to embed in a QR code.
     */
    public String buildOtpAuthUri(String issuer, String accountName, String secret) {
        String label = URLEncoder.encode(issuer + ":" + accountName, StandardCharsets.UTF_8);
        String params = "secret=" + secret
                + "&issuer=" + URLEncoder.encode(issuer, StandardCharsets.UTF_8)
                + "&algorithm=SHA1"
                + "&digits=" + DIGITS
                + "&period=" + STEP_SECONDS;
        return "otpauth://totp/" + label + "?" + params;
    }

    private static long currentStep() {
        return System.currentTimeMillis() / 1000L / STEP_SECONDS;
    }

    private static String computeCode(String base32Secret, long step) {
        byte[] key = base32Decode(base32Secret);
        byte[] data = new byte[8];
        for (int i = 7; i >= 0; i--) {
            data[i] = (byte) (step & 0xff);
            step >>>= 8;
        }
        byte[] hmac;
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            hmac = mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA1 unavailable", e);
        }
        int offset = hmac[hmac.length - 1] & 0xf;
        int binary = ((hmac[offset] & 0x7f) << 24)
                | ((hmac[offset + 1] & 0xff) << 16)
                | ((hmac[offset + 2] & 0xff) << 8)
                | (hmac[offset + 3] & 0xff);
        int otp = binary % DIGIT_POWERS[DIGITS];
        String code = Integer.toString(otp);
        while (code.length() < DIGITS) code = "0" + code;
        return code;
    }

    /**
     * Encodes raw bytes as RFC 4648 Base32 (no padding). Output length is
     * always {@code ceil(input.length * 8 / 5)}.
     */
    static String base32Encode(byte[] data) {
        StringBuilder sb = new StringBuilder((data.length * 8 + 4) / 5);
        long buffer = 0;
        int bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                bitsLeft -= 5;
                sb.append(BASE32_ALPHABET.charAt((int) ((buffer >> bitsLeft) & 0x1f)));
            }
        }
        if (bitsLeft > 0) {
            sb.append(BASE32_ALPHABET.charAt((int) ((buffer << (5 - bitsLeft)) & 0x1f)));
        }
        return sb.toString();
    }

    /**
     * Decodes an RFC 4648 Base32 string. Case-insensitive; ignores '=' padding.
     */
    static byte[] base32Decode(String input) {
        String upper = input.toUpperCase().replaceAll("[^A-Z2-7]", "");
        byte[] out = new byte[upper.length() * 5 / 8];
        long buffer = 0;
        int bitsLeft = 0;
        int outIndex = 0;
        for (int i = 0; i < upper.length(); i++) {
            int value = BASE32_ALPHABET.indexOf(upper.charAt(i));
            if (value < 0) continue;
            buffer = (buffer << 5) | value;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                bitsLeft -= 8;
                out[outIndex++] = (byte) ((buffer >> bitsLeft) & 0xff);
            }
        }
        return out;
    }
}
