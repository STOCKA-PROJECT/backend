package com.stocka.backend.modules.notifications.email;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.stocka.backend.modules.users.entity.Language;

/**
 * {@link EmailService} implementation that delivers transactional emails through Resend.
 *
 * <p>Reuses {@link EmailTemplateRenderer} to keep the same Thymeleaf templates and i18n
 * message bundles as the SMTP/local providers. Idempotency keys are derived from the
 * one-time URL embedded in each email so retries of the same logical event collapse to a
 * single delivery.
 *
 * <p>Active only when {@code app.email.provider=resend}.
 */
@Service
@ConditionalOnProperty(name = "app.email.provider", havingValue = "resend")
public class ResendEmailService implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(ResendEmailService.class);

    static final String INVITATION_KEY_PREFIX = "org-invitation/";
    static final String PASSWORD_RESET_KEY_PREFIX = "password-reset/";
    static final String EMAIL_VERIFICATION_KEY_PREFIX = "email-verification/";
    private static final int IDEMPOTENCY_HASH_HEX_LENGTH = 32;

    private final EmailTemplateRenderer renderer;
    private final ResendSender sender;
    private final String fromAddress;

    public ResendEmailService(
            EmailTemplateRenderer renderer,
            ResendSender sender,
            @Value("${app.email.from}") String fromAddress
    ) {
        this.renderer = renderer;
        this.sender = sender;
        this.fromAddress = fromAddress;
    }

    @Override
    public void sendInvitationEmail(String to, String inviterName, String orgName, String acceptUrl, Language language) {
        RenderedEmail email = renderer.render(
                "invitation",
                "email.invitation.subject",
                new Object[]{inviterName, orgName},
                language.toLocale(),
                Map.of(
                        "inviterName", inviterName,
                        "orgName", orgName,
                        "acceptUrl", acceptUrl
                )
        );

        String idempotencyKey = INVITATION_KEY_PREFIX + sha256Hex(acceptUrl, IDEMPOTENCY_HASH_HEX_LENGTH);
        dispatch(to, email, idempotencyKey);
    }

    @Override
    public void sendPasswordResetEmail(String to, String userName, String resetUrl, Language language) {
        RenderedEmail email = renderer.render(
                "password-reset",
                "email.passwordReset.subject",
                null,
                language.toLocale(),
                Map.of(
                        "userName", userName,
                        "resetUrl", resetUrl
                )
        );

        String idempotencyKey = PASSWORD_RESET_KEY_PREFIX + sha256Hex(resetUrl, IDEMPOTENCY_HASH_HEX_LENGTH);
        dispatch(to, email, idempotencyKey);
    }

    @Override
    public void sendEmailVerification(String to, String userName, String verifyUrl, Language language) {
        RenderedEmail email = renderer.render(
                "email-verification",
                "email.verification.subject",
                null,
                language.toLocale(),
                Map.of(
                        "userName", userName,
                        "verifyUrl", verifyUrl
                )
        );

        String idempotencyKey = EMAIL_VERIFICATION_KEY_PREFIX + sha256Hex(verifyUrl, IDEMPOTENCY_HASH_HEX_LENGTH);
        dispatch(to, email, idempotencyKey);
    }

    /**
     * Delegate to {@link ResendSender} and absorb the final retry failure so the caller's
     * flow (e.g. password-reset request, invitation creation) never breaks because of an
     * email-delivery problem. Matches the "log + continue" pattern already in place for the
     * SMTP and local providers.
     */
    private void dispatch(String to, RenderedEmail email, String idempotencyKey) {
        try {
            sender.send(fromAddress, to, email.subject(), email.htmlBody(), idempotencyKey);
        } catch (ResendTransientException ex) {
            log.error("[RESEND] entrega abortada tras reintentos to={} key={}: {}",
                    to, idempotencyKey, ex.getMessage());
        }
    }

    /**
     * SHA-256 hex prefix of {@code source}, truncated to {@code length} characters. Keeps
     * idempotency keys short (Resend allows up to 256 chars) without leaking the raw token
     * present in the URL.
     *
     * @param source input string (typically the one-time URL)
     * @param length number of hex characters to keep (must be even and ≤ 64)
     * @return prefix of the hex-encoded SHA-256 digest
     */
    private static String sha256Hex(String source, int length) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(source.getBytes(StandardCharsets.UTF_8));
            String hex = HexFormat.of().formatHex(hash);
            return hex.substring(0, Math.min(length, hex.length()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }
}
