package com.stocka.backend.modules.notifications.email.webhook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Processes verified Resend webhook events. Today this is a logging-only sink — the
 * structured logs are intended to feed observability tooling. The class is the natural
 * extension point for persisting bounces/complaints, suppressing recipients in our own
 * database or triggering alerts.
 *
 * <p>Active only when {@code app.email.provider=resend}, alongside the rest of the Resend
 * stack.
 */
@Component
@ConditionalOnProperty(name = "app.email.provider", havingValue = "resend")
@ConditionalOnExpression("'${app.email.resend.webhook-secret:}'.startsWith('whsec_')")
public class ResendWebhookEventHandler {

    private static final Logger log = LoggerFactory.getLogger(ResendWebhookEventHandler.class);

    /**
     * Dispatch a verified Resend event. The Svix signature has already been validated by
     * {@link ResendWebhookController}, so the payload can be trusted.
     *
     * @param event verified webhook event
     */
    public void handle(ResendEvent event) {
        if (event == null || event.type() == null) {
            log.warn("[RESEND WEBHOOK] evento sin tipo, ignorado");
            return;
        }
        switch (event.type()) {
            case "email.sent" -> log.info("[RESEND WEBHOOK] email.sent data={}", event.data());
            case "email.delivered" -> log.info("[RESEND WEBHOOK] email.delivered data={}", event.data());
            case "email.opened" -> log.info("[RESEND WEBHOOK] email.opened data={}", event.data());
            case "email.clicked" -> log.info("[RESEND WEBHOOK] email.clicked data={}", event.data());
            case "email.delivery_delayed" -> log.warn("[RESEND WEBHOOK] email.delivery_delayed data={}", event.data());
            case "email.bounced" -> log.warn("[RESEND WEBHOOK] email.bounced data={}", event.data());
            case "email.complained" -> log.warn("[RESEND WEBHOOK] email.complained data={}", event.data());
            case "email.failed" -> log.error("[RESEND WEBHOOK] email.failed data={}", event.data());
            default -> log.info("[RESEND WEBHOOK] evento no manejado type={} data={}", event.type(), event.data());
        }
    }
}
