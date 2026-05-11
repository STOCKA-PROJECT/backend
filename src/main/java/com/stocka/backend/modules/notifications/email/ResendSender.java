package com.stocka.backend.modules.notifications.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.core.net.RequestOptions;
import com.resend.services.emails.model.CreateEmailOptions;
import com.resend.services.emails.model.CreateEmailResponse;

/**
 * Low-level component that sends a single email through the Resend HTTP API
 * with idempotency-key support and automatic retries on transient failures.
 *
 * <p>Spring Retry only intercepts calls between Spring beans, so the
 * {@code @Retryable} method lives in this dedicated component and is invoked
 * by {@link ResendEmailService}.
 *
 * <p>Active only when {@code app.email.provider=resend}.
 */
@Component
@ConditionalOnProperty(name = "app.email.provider", havingValue = "resend")
public class ResendSender {

    private static final Logger log = LoggerFactory.getLogger(ResendSender.class);

    private final Resend resendClient;

    public ResendSender(Resend resendClient) {
        this.resendClient = resendClient;
    }

    /**
     * Send a rendered email through Resend.
     *
     * <p>Transient failures (HTTP 429, 5xx and unexpected runtime errors) are wrapped in
     * {@link ResendTransientException} so Spring Retry re-invokes this method up to
     * {@code app.email.resend.max-retries} additional times with exponential backoff.
     * Non-transient failures (HTTP 4xx other than 429) are logged and swallowed: the
     * caller's flow is never broken by a malformed payload or duplicate idempotency key.
     *
     * @param from           validated sender address (already includes display name when applicable)
     * @param to             single recipient
     * @param subject        rendered subject
     * @param html           rendered HTML body
     * @param idempotencyKey value sent as {@code Idempotency-Key} header
     * @return Resend message id when the send succeeded, or {@code null} when the failure
     *         was non-transient (logged but not propagated)
     * @throws ResendTransientException to trigger a Spring Retry cycle
     */
    @Retryable(
            retryFor = ResendTransientException.class,
            maxAttemptsExpression = "#{${app.email.resend.max-retries:3} + 1}",
            backoff = @Backoff(
                    delayExpression = "#{${app.email.resend.initial-backoff-ms:500}}",
                    multiplier = 2.0
            )
    )
    public String send(String from, String to, String subject, String html, String idempotencyKey) {
        String safeFrom = EmailHeaders.safeHeader(from);
        String safeTo = EmailHeaders.safeHeader(to);
        String safeSubject = EmailHeaders.safeHeader(subject);
        CreateEmailOptions options = CreateEmailOptions.builder()
                .from(safeFrom)
                .to(safeTo)
                .subject(safeSubject)
                .html(html)
                .build();
        RequestOptions requestOptions = RequestOptions.builder()
                .setIdempotencyKey(idempotencyKey)
                .build();
        try {
            CreateEmailResponse response = resendClient.emails().send(options, requestOptions);
            log.info("[RESEND] enviado id={} to={} key={}", response.getId(), safeTo, idempotencyKey);
            return response.getId();
        } catch (ResendException ex) {
            if (isTransient(ex)) {
                log.warn("[RESEND] error transitorio status={} to={}: {}",
                        ex.getStatusCode(), safeTo, ex.getMessage());
                throw new ResendTransientException(
                        "Resend transient failure (status=" + ex.getStatusCode() + ")", ex);
            }
            log.warn("[RESEND] fallo no recuperable status={} to={} errorName={}: {}",
                    ex.getStatusCode(), safeTo, ex.getErrorName(), ex.getMessage());
            return null;
        } catch (RuntimeException ex) {
            log.warn("[RESEND] error inesperado to={}: {}", safeTo, ex.getMessage());
            throw new ResendTransientException("Resend unexpected runtime failure", ex);
        }
    }

    /**
     * Classify a {@link ResendException} as transient. Anything that is plausibly retryable
     * (network errors with no status, HTTP 429 throttling, HTTP 5xx) is transient. HTTP 4xx
     * other than 429 (validation, auth, idempotency conflict) is not.
     *
     * @param ex exception thrown by the Resend SDK
     * @return {@code true} if the request can be safely retried with the same payload
     */
    private static boolean isTransient(ResendException ex) {
        Integer status = ex.getStatusCode();
        if (status == null) {
            return true;
        }
        return status == 429 || status >= 500;
    }
}
