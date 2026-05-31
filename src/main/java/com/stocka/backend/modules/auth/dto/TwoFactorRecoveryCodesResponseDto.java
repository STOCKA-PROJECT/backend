package com.stocka.backend.modules.auth.dto;

import java.util.List;

/**
 * Response carrying the freshly-minted recovery codes. Returned exactly once
 * from {@code POST /auth/2fa/confirm} and {@code POST
 * /auth/2fa/recovery-codes/regenerate} — the backend only stores BCrypt
 * hashes, so the codes cannot be retrieved again.
 */
public record TwoFactorRecoveryCodesResponseDto(List<String> recoveryCodes) {}
