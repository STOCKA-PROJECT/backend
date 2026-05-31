package com.stocka.backend.modules.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.stocka.backend.modules.auth.entity.EmailVerificationToken;
import com.stocka.backend.modules.auth.repository.EmailVerificationTokenRepository;
import com.stocka.backend.modules.common.error.ApiException;
import com.stocka.backend.modules.common.error.ErrorCodes;
import com.stocka.backend.modules.notifications.email.EmailService;
import com.stocka.backend.modules.security.audit.SecurityAuditService;
import com.stocka.backend.modules.security.audit.SecurityEventType;
import com.stocka.backend.modules.users.entity.User;
import com.stocka.backend.modules.users.repository.UserRepository;

/**
 * Issues and consumes one-time tokens used to confirm a user's email address.
 *
 * <p>The flow mirrors {@link PasswordResetService}: a 32-byte SecureRandom token is
 * generated, stored only as its SHA-256 hash, and the raw value is embedded in a URL
 * that is delivered through {@link EmailService}. {@link #verify(String)} consumes the
 * token, flips {@code emailVerified} to {@code true}, and marks {@code usedAt}.
 *
 * <p>{@link #requestResend(String)} is the public entry point used for the "did not
 * receive the email?" UX. It always returns silently (anti-enumeration) so the caller
 * cannot infer whether the email is registered or already verified.
 */
@Service
public class EmailVerificationService {

    private static final int TOKEN_BYTES = 32;

    private final UserRepository userRepository;
    private final EmailVerificationTokenRepository tokenRepository;
    private final EmailService emailService;
    private final SecurityAuditService securityAuditService;
    private final SecureRandom secureRandom = new SecureRandom();
    private final long ttlMinutes;
    private final String frontendBaseUrl;

    public EmailVerificationService(
            UserRepository userRepository,
            EmailVerificationTokenRepository tokenRepository,
            EmailService emailService,
            SecurityAuditService securityAuditService,
            @Value("${app.email-verification.token-ttl-minutes:1440}") long ttlMinutes,
            @Value("${app.frontend.base-url}") String frontendBaseUrl) {
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.emailService = emailService;
        this.securityAuditService = securityAuditService;
        this.ttlMinutes = ttlMinutes;
        this.frontendBaseUrl = frontendBaseUrl;
    }

    /**
     * Generates a fresh single-use verification token for the given user and dispatches
     * the verification email. Any previous tokens for the same user are deleted so only
     * one is valid at a time.
     *
     * @param user the user that just signed up (or asked for a resend); must be persisted
     */
    public void sendVerificationEmail(User user) {
        tokenRepository.deleteAllByUser(user);

        String rawToken = generateRawToken();
        LocalDateTime now = LocalDateTime.now();

        EmailVerificationToken token = new EmailVerificationToken()
                .setUser(user)
                .setTokenHash(hashToken(rawToken))
                .setExpiresAt(now.plusMinutes(ttlMinutes))
                .setCreatedAt(now);
        tokenRepository.save(token);

        String verifyUrl = buildVerifyUrl(rawToken);
        emailService.sendEmailVerification(user.getEmail(), user.getName(), verifyUrl, user.getLanguage());
    }

    /**
     * Validates the token, marks the user as verified and burns the token. Idempotent
     * when the user is already verified (the token is still consumed so it cannot be
     * reused, but no other side effects occur).
     *
     * @param rawToken token raw value as it travels in the email URL
     * @throws ApiException with code {@link ErrorCodes#AUTH_VERIFICATION_TOKEN_INVALID}
     *         when the token is missing, unknown, expired or already used
     */
    public void verify(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.AUTH_VERIFICATION_TOKEN_INVALID);
        }

        EmailVerificationToken token = tokenRepository.findByTokenHash(hashToken(rawToken))
                .orElseThrow(() -> new ApiException(
                        HttpStatus.BAD_REQUEST, ErrorCodes.AUTH_VERIFICATION_TOKEN_INVALID));

        LocalDateTime now = LocalDateTime.now();
        if (token.getUsedAt() != null || token.getExpiresAt().isBefore(now)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.AUTH_VERIFICATION_TOKEN_INVALID);
        }

        User user = token.getUser();
        boolean newlyVerified = !user.isEmailVerified();
        if (newlyVerified) {
            user.setEmailVerified(true);
            userRepository.save(user);
        }

        token.setUsedAt(now);
        tokenRepository.save(token);

        if (newlyVerified) {
            securityAuditService.recordSuccess(SecurityEventType.EMAIL_VERIFIED, user);
        }
    }

    /**
     * Re-issues a verification email for the given address. Always returns silently —
     * unknown emails, blank input or already-verified accounts produce no observable
     * side effect, so the endpoint cannot be used to enumerate registered users.
     *
     * @param email candidate email; may be {@code null} or blank
     */
    public void requestResend(String email) {
        if (email == null || email.isBlank()) {
            return;
        }

        Optional<User> maybeUser = userRepository.findByEmail(email);
        if (maybeUser.isEmpty()) {
            return;
        }

        User user = maybeUser.get();
        if (user.isEmailVerified()) {
            return;
        }

        sendVerificationEmail(user);
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

    private String buildVerifyUrl(String rawToken) {
        String base = frontendBaseUrl.endsWith("/") ? frontendBaseUrl.substring(0, frontendBaseUrl.length() - 1)
                : frontendBaseUrl;
        return base + "/verificar-email?token=" + rawToken;
    }
}
