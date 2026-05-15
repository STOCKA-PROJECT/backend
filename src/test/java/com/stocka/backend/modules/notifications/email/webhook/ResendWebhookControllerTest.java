package com.stocka.backend.modules.notifications.email.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.net.http.HttpHeaders;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.svix.Webhook;
import com.svix.exceptions.WebhookVerificationException;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("ResendWebhookController")
class ResendWebhookControllerTest {

    @Mock private Webhook svixWebhook;
    @Mock private ResendWebhookEventHandler handler;

    private ResendWebhookController sut;
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @BeforeEach
    void setUp() {
        sut = new ResendWebhookController(svixWebhook, handler, objectMapper);
    }

    @Nested
    @DisplayName("signature validation")
    class SignatureValidation {

        @Test
        @DisplayName("returns 200 OK when the Svix signature is valid")
        void should_return200_whenSignatureValid() throws Exception {
            doNothing().when(svixWebhook).verify(anyString(), any(HttpHeaders.class));
            String payload = """
                    {"type":"email.delivered","created_at":"ts","data":{}}
                    """;

            ResponseEntity<Void> response = sut.receive(payload, "msg_1", "1700000000", "v1,sig");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("returns 401 UNAUTHORIZED when the signature does not validate")
        void should_return401_whenSignatureInvalid() throws Exception {
            doThrow(new WebhookVerificationException("bad signature"))
                    .when(svixWebhook).verify(anyString(), any(HttpHeaders.class));

            ResponseEntity<Void> response = sut.receive("{}", "msg_1", "ts", "sig");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            verify(handler, never()).handle(any());
        }

        @Test
        @DisplayName("forwards the raw body and svix headers to the verifier verbatim")
        void should_forwardRawPayloadToVerifier() throws Exception {
            doNothing().when(svixWebhook).verify(anyString(), any(HttpHeaders.class));
            ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<HttpHeaders> headersCaptor = ArgumentCaptor.forClass(HttpHeaders.class);

            String raw = "{\"type\":\"email.sent\",\"created_at\":\"x\",\"data\":{}}";
            sut.receive(raw, "msg_42", "1700000123", "v1,abcdef");

            verify(svixWebhook).verify(payloadCaptor.capture(), headersCaptor.capture());
            assertThat(payloadCaptor.getValue()).isEqualTo(raw);
            HttpHeaders forwarded = headersCaptor.getValue();
            assertThat(forwarded.firstValue("svix-id")).contains("msg_42");
            assertThat(forwarded.firstValue("svix-timestamp")).contains("1700000123");
            assertThat(forwarded.firstValue("svix-signature")).contains("v1,abcdef");
        }
    }

    @Nested
    @DisplayName("event dispatching")
    class EventDispatching {

        @Test
        @DisplayName("parses the payload and forwards it to the handler when the signature is valid")
        void should_invokeHandler_withParsedEvent() throws Exception {
            doNothing().when(svixWebhook).verify(anyString(), any(HttpHeaders.class));
            String payload = """
                    {
                      "type": "email.delivered",
                      "created_at": "2026-05-03T12:00:00Z",
                      "data": { "email_id": "abc-123", "to": ["x@y"] }
                    }
                    """;
            ArgumentCaptor<ResendEvent> eventCaptor = ArgumentCaptor.forClass(ResendEvent.class);

            sut.receive(payload, "msg_1", "ts", "sig");

            verify(handler).handle(eventCaptor.capture());
            ResendEvent captured = eventCaptor.getValue();
            assertThat(captured.type()).isEqualTo("email.delivered");
            assertThat(captured.createdAt()).isEqualTo("2026-05-03T12:00:00Z");
            assertThat(captured.data()).containsEntry("email_id", "abc-123");
        }

        @Test
        @DisplayName("ignores unknown top-level fields in the payload")
        void should_ignoreUnknownFields() throws Exception {
            doNothing().when(svixWebhook).verify(anyString(), any(HttpHeaders.class));
            String payload = """
                    {
                      "type": "email.delivered",
                      "created_at": "ts",
                      "data": {},
                      "future_field": "to-be-ignored"
                    }
                    """;

            ResponseEntity<Void> response = sut.receive(payload, "msg_1", "ts", "sig");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(handler).handle(any(ResendEvent.class));
        }

        @Test
        @DisplayName("returns 400 BAD REQUEST when the JSON body is malformed")
        void should_return400_whenJsonInvalid() throws Exception {
            doNothing().when(svixWebhook).verify(anyString(), any(HttpHeaders.class));

            ResponseEntity<Void> response = sut.receive("{not json", "msg_1", "ts", "sig");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            verify(handler, never()).handle(any());
        }

        @Test
        @DisplayName("returns 200 even when the same svix-id is delivered twice (handler decides idempotency)")
        void should_return200_onDuplicateSvixId() throws Exception {
            doNothing().when(svixWebhook).verify(anyString(), any(HttpHeaders.class));
            String payload = """
                    {"type":"email.sent","created_at":"ts","data":{}}
                    """;

            ResponseEntity<Void> first = sut.receive(payload, "msg_dup", "ts", "sig");
            ResponseEntity<Void> second = sut.receive(payload, "msg_dup", "ts", "sig");

            assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(handler, org.mockito.Mockito.times(2)).handle(any(ResendEvent.class));
        }
    }
}
