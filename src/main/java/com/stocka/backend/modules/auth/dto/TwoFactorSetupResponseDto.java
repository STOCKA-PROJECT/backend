package com.stocka.backend.modules.auth.dto;

/**
 * Response of {@code POST /auth/2fa/setup}. The frontend renders {@code
 * otpAuthUri} as a QR and exposes {@code secret} as a fallback for the
 * "type it manually" path.
 *
 * @param setupToken opaque token to send back to {@code /auth/2fa/confirm}
 * @param secret Base32 TOTP secret (also embedded in {@code otpAuthUri})
 * @param otpAuthUri full {@code otpauth://totp/...} URI for QR rendering
 */
public record TwoFactorSetupResponseDto(String setupToken, String secret, String otpAuthUri) {}
