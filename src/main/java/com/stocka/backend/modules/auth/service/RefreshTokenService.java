package com.stocka.backend.modules.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.stocka.backend.modules.auth.entity.RefreshToken;
import com.stocka.backend.modules.auth.entity.RefreshToken.RevocationReason;
import com.stocka.backend.modules.auth.repository.RefreshTokenRepository;
import com.stocka.backend.modules.common.error.ApiException;
import com.stocka.backend.modules.common.error.ErrorCodes;
import com.stocka.backend.modules.security.config.RefreshTokenProperties;
import com.stocka.backend.modules.users.entity.User;

/**
 * Mints, rotates and revokes long-lived refresh tokens. The raw token never
 * touches the database: only its SHA-256 hex hash is persisted. Each rotation
 * keeps the {@code familyId} of the original login; the family is revoked on
 * reuse detection.
 *
 * <p>The service is intentionally agnostic of HTTP — cookie handling lives in
 * the controller. The {@link IssuedRefreshToken} record exposes both the raw
 * value (to be sent to the client) and the stored entity (for downstream
 * consumers such as audit logging in F3).
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    private static final int TOKEN_BYTES = 48;

    private final RefreshTokenRepository repository;
    private final RefreshTokenProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    public RefreshTokenService(RefreshTokenRepository repository, RefreshTokenProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    /**
     * Outcome of {@link #issueForLogin(User, boolean)} and
     * {@link #rotate(String, String)} — carries both the raw token (which must
     * be sent to the client exactly once) and the persisted row.
     *
     * @param rawToken value to put in the {@code stocka_refresh} cookie
     * @param entity   persisted row
     */
    public record IssuedRefreshToken(String rawToken, RefreshToken entity) {}

    /**
     * Issues a brand-new refresh token for a fresh login. Generates a new
     * {@code familyId}; pick {@link #rotate(String, String)} for follow-up
     * rotations.
     *
     * @param user       user that just authenticated
     * @param rememberMe whether the "remember me" checkbox was ticked
     * @return raw token and stored entity
     */
    @Transactional
    public IssuedRefreshToken issueForLogin(User user, boolean rememberMe) {
        return issue(user, UUID.randomUUID().toString(), rememberMe);
    }

    /**
     * Rotates a refresh token presented at {@code /auth/refresh}. Marks the
     * incoming token revoked atomically — when the update affects 0 rows the
     * token was already revoked (i.e. someone is replaying it) and the whole
     * family is wiped.
     *
     * <p>{@code noRollbackFor = ApiException.class} is critical: when reuse or
     * expiration is detected we mutate the database (revoke the family or the
     * expired row) and then surface an {@link ApiException}. Without this
     * setting Spring would roll back the revocation along with the exception,
     * leaving the compromised token still active.
     *
     * @param presentedRawToken raw token from the {@code stocka_refresh} cookie
     * @param userAgent         caller's UA — kept for audit logging in F3 (unused here)
     * @return new raw token and stored entity
     * @throws ApiException with code {@link ErrorCodes#AUTH_REFRESH_TOKEN_INVALID}
     *                      when the token is unknown
     * @throws ApiException with code {@link ErrorCodes#AUTH_REFRESH_TOKEN_EXPIRED}
     *                      when the token is past its expiration
     * @throws ApiException with code {@link ErrorCodes#AUTH_REFRESH_TOKEN_REUSED}
     *                      when reuse is detected; the family is revoked
     */
    @Transactional(noRollbackFor = ApiException.class)
    public IssuedRefreshToken rotate(String presentedRawToken, String userAgent) {
        if (presentedRawToken == null || presentedRawToken.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.AUTH_REFRESH_TOKEN_MISSING);
        }
        String presentedHash = hashToken(presentedRawToken);
        Optional<RefreshToken> maybe = repository.findByTokenHash(presentedHash);
        if (maybe.isEmpty()) {
            log.info("refresh_token_invalid hash_prefix={}", presentedHash.substring(0, 8));
            throw new ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.AUTH_REFRESH_TOKEN_INVALID);
        }

        RefreshToken stored = maybe.get();
        LocalDateTime now = LocalDateTime.now();

        if (stored.getRevokedAt() != null) {
            int wiped = repository.revokeFamily(stored.getFamilyId(), RevocationReason.REUSE_DETECTED, now);
            log.warn("refresh_token_reuse_detected user_id={} family_id={} wiped={}",
                    stored.getUser().getId(), stored.getFamilyId(), wiped);
            throw new ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.AUTH_REFRESH_TOKEN_REUSED);
        }

        if (!stored.getExpiresAt().isAfter(now)) {
            stored.setRevokedAt(now).setRevokedReason(RevocationReason.ROTATED);
            repository.save(stored);
            throw new ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.AUTH_REFRESH_TOKEN_EXPIRED);
        }

        stored.setRevokedAt(now).setRevokedReason(RevocationReason.ROTATED);
        IssuedRefreshToken next = issue(stored.getUser(), stored.getFamilyId(), stored.isRememberMe());
        stored.setReplacedByHash(next.entity().getTokenHash());
        repository.save(stored);
        return next;
    }

    /**
     * Revokes a single refresh token (logout). The whole family is left
     * untouched — only this device is signed out.
     *
     * @param rawToken raw token from the cookie; may be {@code null} or unknown
     */
    @Transactional
    public void revokeByRawToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        repository.findByTokenHash(hashToken(rawToken)).ifPresent(token -> {
            if (token.getRevokedAt() == null) {
                token.setRevokedAt(LocalDateTime.now()).setRevokedReason(RevocationReason.LOGOUT);
                repository.save(token);
            }
        });
    }

    /**
     * Revokes every still-active refresh token for the given user. Called on
     * password change so a leaked password can't keep granting fresh access
     * tokens.
     *
     * @param user   user whose sessions are being terminated
     * @param reason why the sessions are going away (audit-friendly)
     */
    @Transactional
    public void revokeAllForUser(User user, RevocationReason reason) {
        int revoked = repository.revokeAllForUser(user, reason, LocalDateTime.now());
        if (revoked > 0) {
            log.info("refresh_tokens_revoked user_id={} reason={} count={}",
                    user.getId(), reason, revoked);
        }
    }

    private IssuedRefreshToken issue(User user, String familyId, boolean rememberMe) {
        String rawToken = generateRawToken();
        LocalDateTime now = LocalDateTime.now();
        int ttlDays = rememberMe
                ? properties.getRememberTtlDays()
                : properties.getSessionTtlDays();

        RefreshToken entity = new RefreshToken()
                .setUser(user)
                .setTokenHash(hashToken(rawToken))
                .setFamilyId(familyId)
                .setIssuedAt(now)
                .setExpiresAt(now.plusDays(ttlDays))
                .setRememberMe(rememberMe);
        RefreshToken saved = repository.save(entity);
        return new IssuedRefreshToken(rawToken, saved);
    }

    private String generateRawToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible en la JVM", e);
        }
    }
}
