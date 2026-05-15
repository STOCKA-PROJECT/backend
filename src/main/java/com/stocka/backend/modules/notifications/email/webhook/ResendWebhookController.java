package com.stocka.backend.modules.notifications.email.webhook;

import java.net.http.HttpHeaders;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.svix.Webhook;
import com.svix.exceptions.WebhookVerificationException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Public endpoint that receives Resend webhook events.
 *
 * <p>Resend signs every webhook with Svix; we verify the signature on every request and
 * reject anything we cannot prove came from Resend. The raw request body must be passed
 * unmodified to {@link Webhook#verify(String, HttpHeaders)} — that's why the controller
 * accepts a {@link String} body instead of a parsed JSON object.
 *
 * <p>Active only when {@code app.email.provider=resend}.
 */
@RestController
@RequestMapping("/webhooks/resend")
@ConditionalOnProperty(name = "app.email.provider", havingValue = "resend")
@ConditionalOnExpression("'${app.email.resend.webhook-secret:}'.startsWith('whsec_')")
public class ResendWebhookController {

    private static final Logger log = LoggerFactory.getLogger(ResendWebhookController.class);

    private final Webhook svixWebhook;
    private final ResendWebhookEventHandler handler;
    private final ObjectMapper objectMapper;

    public ResendWebhookController(
            Webhook svixWebhook,
            ResendWebhookEventHandler handler,
            ObjectMapper objectMapper
    ) {
        this.svixWebhook = svixWebhook;
        this.handler = handler;
        this.objectMapper = objectMapper;
    }

    /**
     * Receive and dispatch a Resend webhook event.
     *
     * @param rawPayload      raw JSON body — must be the exact bytes sent by Resend
     * @param svixId          {@code svix-id} header (unique message id)
     * @param svixTimestamp   {@code svix-timestamp} header (Unix seconds)
     * @param svixSignature   {@code svix-signature} header (cryptographic signature)
     * @return 200 OK when the signature is valid and the event was dispatched;
     *         401 when the signature does not validate;
     *         400 when the JSON cannot be parsed.
     */
    @PostMapping
    public ResponseEntity<Void> receive(
            @RequestBody String rawPayload,
            @RequestHeader("svix-id") String svixId,
            @RequestHeader("svix-timestamp") String svixTimestamp,
            @RequestHeader("svix-signature") String svixSignature
    ) {
        HttpHeaders headers = HttpHeaders.of(
                Map.of(
                        "svix-id", List.of(svixId),
                        "svix-timestamp", List.of(svixTimestamp),
                        "svix-signature", List.of(svixSignature)
                ),
                (k, v) -> true
        );
        try {
            svixWebhook.verify(rawPayload, headers);
        } catch (WebhookVerificationException e) {
            log.warn("[RESEND WEBHOOK] firma inválida svixId={}: {}", svixId, e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        ResendEvent event;
        try {
            event = objectMapper.readValue(rawPayload, ResendEvent.class);
        } catch (JacksonException e) {
            log.warn("[RESEND WEBHOOK] payload mal formado svixId={}: {}", svixId, e.getMessage());
            return ResponseEntity.badRequest().build();
        }

        handler.handle(event);
        return ResponseEntity.ok().build();
    }
}
