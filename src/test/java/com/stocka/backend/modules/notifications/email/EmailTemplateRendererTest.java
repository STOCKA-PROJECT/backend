package com.stocka.backend.modules.notifications.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@DisplayName("EmailTemplateRenderer")
class EmailTemplateRendererTest {

    @Autowired private EmailTemplateRenderer renderer;

    @Nested
    @DisplayName("invitation template")
    class Invitation {

        @Test
        @DisplayName("should render with all variables interpolated")
        void should_renderInvitationWithVariables() {
            RenderedEmail rendered = renderer.render(
                    "invitation",
                    "You're invited",
                    Map.of(
                            "inviterName", "Joan",
                            "orgName", "Acme Corp",
                            "acceptUrl", "https://app/invitations/abc123"
                    )
            );

            assertEquals("You're invited", rendered.subject());
            assertNotNull(rendered.htmlBody());
            assertTrue(rendered.htmlBody().contains("Joan"));
            assertTrue(rendered.htmlBody().contains("Acme Corp"));
            assertTrue(rendered.htmlBody().contains("https://app/invitations/abc123"));
        }

        @Test
        @DisplayName("should not throw when variables are missing")
        void should_notThrow_when_variablesMissing() {
            RenderedEmail rendered = renderer.render(
                    "invitation",
                    "subject",
                    new HashMap<>()
            );
            assertNotNull(rendered.htmlBody());
        }
    }

    @Nested
    @DisplayName("password-reset template")
    class PasswordReset {

        @Test
        @DisplayName("should render with userName and resetUrl interpolated")
        void should_renderPasswordResetWithVariables() {
            RenderedEmail rendered = renderer.render(
                    "password-reset",
                    "Restablece tu contraseña",
                    Map.of(
                            "userName", "Joan",
                            "resetUrl", "https://app/reset-password?token=xyz789"
                    )
            );

            assertEquals("Restablece tu contraseña", rendered.subject());
            assertNotNull(rendered.htmlBody());
            assertTrue(rendered.htmlBody().contains("Joan"));
            assertTrue(rendered.htmlBody().contains("https://app/reset-password?token=xyz789"));
        }

        @Test
        @DisplayName("should include the CTA button anchor pointing to the resetUrl")
        void should_renderCtaButton_pointingToResetUrl() {
            RenderedEmail rendered = renderer.render(
                    "password-reset",
                    "subject",
                    Map.of(
                            "userName", "Joan",
                            "resetUrl", "https://app/reset-password?token=abc"
                    )
            );

            assertTrue(rendered.htmlBody().contains("href=\"https://app/reset-password?token=abc\""),
                    "expected CTA anchor to use resetUrl");
        }

        @Test
        @DisplayName("should mention the 30-minute single-use guarantee in the body")
        void should_mentionTtlAndSingleUseInBody() {
            RenderedEmail rendered = renderer.render(
                    "password-reset",
                    "subject",
                    Map.of("userName", "Joan", "resetUrl", "http://x")
            );

            assertTrue(rendered.htmlBody().contains("30 minutos"),
                    "body should tell the user about the 30-minute TTL");
        }

        @Test
        @DisplayName("should not throw when variables are missing")
        void should_notThrow_when_variablesMissing() {
            RenderedEmail rendered = renderer.render(
                    "password-reset",
                    "subject",
                    new HashMap<>()
            );
            assertNotNull(rendered.htmlBody());
        }
    }

    @Nested
    @DisplayName("shared layout")
    class SharedLayout {

        @Test
        @DisplayName("invitation footer should use the generic 'cuenta en Stocka' wording (not the old invitation-specific text)")
        void invitation_should_useGenericFooter() {
            RenderedEmail rendered = renderer.render(
                    "invitation",
                    "subject",
                    Map.of("inviterName", "i", "orgName", "o", "acceptUrl", "u")
            );

            assertTrue(rendered.htmlBody().contains("Recibiste este email porque tienes una cuenta en Stocka"),
                    "footer should contain the generic wording");
            assertTrue(!rendered.htmlBody().contains("Recibiste este email porque alguien te invit"),
                    "old invitation-only footer should be gone");
        }

        @Test
        @DisplayName("password-reset footer should use the same generic wording")
        void passwordReset_should_useGenericFooter() {
            RenderedEmail rendered = renderer.render(
                    "password-reset",
                    "subject",
                    Map.of("userName", "Joan", "resetUrl", "http://x")
            );

            assertTrue(rendered.htmlBody().contains("Recibiste este email porque tienes una cuenta en Stocka"),
                    "footer should be the generic shared wording");
        }

        @Test
        @DisplayName("layout header should still display the Stocka brand")
        void layout_should_renderHeader() {
            RenderedEmail rendered = renderer.render(
                    "password-reset",
                    "subject",
                    Map.of("userName", "Joan", "resetUrl", "http://x")
            );

            assertTrue(rendered.htmlBody().contains("background-color:#111827"),
                    "header bar (dark background) should be rendered");
            assertTrue(rendered.htmlBody().contains("Stocka"),
                    "Stocka brand label should appear somewhere in the rendered email");
        }
    }
}
