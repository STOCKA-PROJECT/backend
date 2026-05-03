package com.stocka.backend.modules.notifications.email.webhook;

import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("ResendWebhookEventHandler")
class ResendWebhookEventHandlerTest {

    private ResendWebhookEventHandler sut;

    @BeforeEach
    void setUp() {
        sut = new ResendWebhookEventHandler();
    }

    @Nested
    @DisplayName("handle")
    class Handle {

        @ParameterizedTest(name = "does not throw for known event type: {0}")
        @ValueSource(strings = {
                "email.sent",
                "email.delivered",
                "email.opened",
                "email.clicked",
                "email.delivery_delayed",
                "email.bounced",
                "email.complained",
                "email.failed"
        })
        void should_handleAllKnownEventTypes(String type) {
            ResendEvent event = new ResendEvent(type, "2026-05-03T12:00:00Z", Map.of("email_id", "abc"));

            assertThatCode(() -> sut.handle(event)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("does not throw for an unknown event type")
        void should_notThrow_forUnknownType() {
            ResendEvent event = new ResendEvent("email.future_event", "ts", Map.of());

            assertThatCode(() -> sut.handle(event)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("does not throw when the event is null")
        void should_notThrow_whenEventIsNull() {
            assertThatCode(() -> sut.handle(null)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("does not throw when the event has a null type")
        void should_notThrow_whenTypeIsNull() {
            ResendEvent event = new ResendEvent(null, "ts", Map.of());

            assertThatCode(() -> sut.handle(event)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("does not throw when the event data is null")
        void should_notThrow_whenDataIsNull() {
            ResendEvent event = new ResendEvent("email.delivered", "ts", null);

            assertThatCode(() -> sut.handle(event)).doesNotThrowAnyException();
        }
    }
}
