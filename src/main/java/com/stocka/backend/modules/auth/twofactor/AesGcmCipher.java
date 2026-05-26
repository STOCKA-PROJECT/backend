package com.stocka.backend.modules.auth.twofactor;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * AES-GCM cipher used to protect TOTP secrets at rest. The IV is randomized
 * per encryption (12 bytes) and prepended to the ciphertext + tag; the whole
 * blob is then Base64-url encoded so it fits in a {@code VARCHAR} column.
 *
 * <p>Key material is loaded from {@code security.twofactor.encryption-key}
 * (64 hex chars / 256 bits). The application fails fast at startup if the
 * key is missing or malformed — TOTP secrets in plain text would be a
 * disaster.
 */
@Component
public class AesGcmCipher {

    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final int MIN_HEX_LENGTH = 64;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    @Value("${security.twofactor.encryption-key:}")
    private String encryptionKeyHex;

    private final SecureRandom secureRandom = new SecureRandom();
    private SecretKeySpec key;

    @PostConstruct
    void initialize() {
        if (encryptionKeyHex == null || encryptionKeyHex.isBlank()) {
            throw new IllegalStateException(
                    "security.twofactor.encryption-key is required. Set TWOFACTOR_ENCRYPTION_KEY "
                            + "to a 64-character hex string (generate with: openssl rand -hex 32).");
        }
        if (encryptionKeyHex.length() < MIN_HEX_LENGTH) {
            throw new IllegalStateException(
                    "security.twofactor.encryption-key must be at least " + MIN_HEX_LENGTH
                            + " hex characters (256 bits); got " + encryptionKeyHex.length() + ".");
        }
        if (encryptionKeyHex.length() % 2 != 0 || !encryptionKeyHex.matches("\\p{XDigit}+")) {
            throw new IllegalStateException(
                    "security.twofactor.encryption-key must be a valid hexadecimal string.");
        }
        byte[] keyBytes = HexFormat.of().parseHex(encryptionKeyHex);
        this.key = new SecretKeySpec(keyBytes, "AES");
    }

    /**
     * Encrypts a UTF-8 string. The output is Base64-url-encoded and includes
     * the random IV.
     *
     * @param plaintext value to encrypt
     * @return Base64-url-encoded {@code iv || ciphertext || tag}
     */
    public String encryptToString(String plaintext) {
        byte[] iv = new byte[IV_LENGTH];
        secureRandom.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] cipherText = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(cipherText, 0, out, iv.length, cipherText.length);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM encryption failed", e);
        }
    }

    /**
     * Decrypts a value produced by {@link #encryptToString(String)}.
     *
     * @param encoded Base64-url-encoded {@code iv || ciphertext || tag}
     * @return original UTF-8 plaintext
     */
    public String decryptFromString(String encoded) {
        byte[] blob = Base64.getUrlDecoder().decode(encoded);
        if (blob.length <= IV_LENGTH) {
            throw new IllegalStateException("ciphertext too short");
        }
        byte[] iv = new byte[IV_LENGTH];
        System.arraycopy(blob, 0, iv, 0, IV_LENGTH);
        byte[] cipherText = new byte[blob.length - IV_LENGTH];
        System.arraycopy(blob, IV_LENGTH, cipherText, 0, cipherText.length);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM decryption failed", e);
        }
    }
}
