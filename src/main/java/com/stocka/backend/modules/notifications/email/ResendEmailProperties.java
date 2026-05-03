package com.stocka.backend.modules.notifications.email;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the Resend email provider, bound from {@code app.email.resend.*}.
 *
 * @param apiKey         Resend API key (format {@code re_...}). Required when {@code app.email.provider=resend}.
 * @param webhookSecret  Svix signing secret (format {@code whsec_...}) used to verify incoming webhook requests.
 * @param maxRetries     Number of additional attempts on transient failures (HTTP 429/5xx, network errors).
 *                       Total attempts = {@code maxRetries + 1}.
 * @param initialBackoffMs Initial delay between retries in milliseconds. Subsequent delays grow with multiplier 2.0.
 */
@ConfigurationProperties("app.email.resend")
public record ResendEmailProperties(
        String apiKey,
        String webhookSecret,
        int maxRetries,
        long initialBackoffMs
) {
    public ResendEmailProperties {
        if (maxRetries < 0) {
            maxRetries = 0;
        }
        if (initialBackoffMs < 0) {
            initialBackoffMs = 0;
        }
    }
}
