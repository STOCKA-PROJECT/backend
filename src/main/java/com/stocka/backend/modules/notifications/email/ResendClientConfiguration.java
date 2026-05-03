package com.stocka.backend.modules.notifications.email;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.resend.Resend;
import com.svix.Webhook;

/**
 * Wires the Resend SDK client and the Svix webhook verifier as Spring beans.
 *
 * <p>The {@link Resend} client is required whenever {@code app.email.provider=resend}.
 * The {@link Webhook} verifier is optional: it is only registered when
 * {@code app.email.resend.webhook-secret} is set to a non-empty value, so the backend can
 * run with Resend enabled for outgoing email even before the webhook endpoint is wired up.
 */
@Configuration
@ConditionalOnProperty(name = "app.email.provider", havingValue = "resend")
@EnableConfigurationProperties(ResendEmailProperties.class)
public class ResendClientConfiguration {

    @Bean
    public Resend resendClient(ResendEmailProperties properties) {
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            throw new IllegalStateException(
                    "Falta la propiedad app.email.resend.api-key (RESEND_API_KEY) cuando app.email.provider=resend"
            );
        }
        return new Resend(properties.apiKey());
    }

    /**
     * Build the Svix verifier only when a non-empty signing secret is configured. Leaving
     * {@code RESEND_WEBHOOK_SECRET} blank means "no webhook yet" — the verifier bean is
     * skipped and {@code ResendWebhookController} is not registered (its conditional has
     * the same expression), so the rest of the Resend stack still works.
     */
    @Bean
    @ConditionalOnExpression("'${app.email.resend.webhook-secret:}'.startsWith('whsec_')")
    public Webhook svixWebhook(ResendEmailProperties properties) {
        try {
            return new Webhook(properties.webhookSecret());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "El valor de RESEND_WEBHOOK_SECRET no es un secreto Svix válido. "
                            + "Debe empezar por 'whsec_' seguido de una cadena base64 estándar "
                            + "(alfabeto [A-Za-z0-9+/], sin guiones bajos). "
                            + "Cópialo desde https://resend.com/webhooks → tu webhook → Signing Secret.",
                    e
            );
        }
    }
}
