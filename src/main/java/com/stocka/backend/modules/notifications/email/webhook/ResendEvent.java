package com.stocka.backend.modules.notifications.email.webhook;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Minimal projection of a Resend webhook payload.
 *
 * <p>Resend payloads have the form
 * {@code { "type": "email.delivered", "created_at": "...", "data": { ... } }}.
 * The {@code data} object varies by event type and is therefore kept as an open map so
 * downstream handlers can inspect whichever fields they need (e.g. {@code email_id},
 * {@code from}, {@code to}, {@code subject}, {@code bounce.subType}).
 *
 * <p>Unknown top-level fields are ignored to stay forward-compatible with new event shapes.
 *
 * @param type      Resend event type, e.g. {@code email.delivered}, {@code email.bounced}
 * @param createdAt ISO-8601 timestamp of when the event was emitted
 * @param data      event-specific payload
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ResendEvent(
        String type,
        @JsonProperty("created_at") String createdAt,
        Map<String, Object> data
) {
}
