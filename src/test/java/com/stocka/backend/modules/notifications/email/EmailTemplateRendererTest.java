package com.stocka.backend.modules.notifications.email;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@DisplayName("EmailTemplateRenderer")
class EmailTemplateRendererTest {

        @Autowired
        private EmailTemplateRenderer renderer;

        private static final Locale ES = Locale.of("es");
        private static final Locale EN = Locale.ENGLISH;
        private static final Locale CA = Locale.of("ca");

        // -------------------------------------------------------------------------
        // subject resolution (tests the MessageSource integration directly)
        // -------------------------------------------------------------------------

        @Nested
        @DisplayName("subject")
        class Subject {

                @Test
                @DisplayName("should resolve the invitation subject in ES with args interpolated")
                void should_resolveInvitationSubject_inEs() {
                        RenderedEmail rendered = renderer.render(
                                        "invitation",
                                        "email.invitation.subject",
                                        new Object[] { "Joan", "Acme" },
                                        ES,
                                        Map.of("inviterName", "Joan", "orgName", "Acme", "acceptUrl", "http://x"));
                        assertEquals("Joan te ha invitado a Acme en Stocka", rendered.subject());
                }

                @Test
                @DisplayName("should resolve the invitation subject in EN with args interpolated")
                void should_resolveInvitationSubject_inEn() {
                        RenderedEmail rendered = renderer.render(
                                        "invitation",
                                        "email.invitation.subject",
                                        new Object[] { "Joan", "Acme" },
                                        EN,
                                        Map.of("inviterName", "Joan", "orgName", "Acme", "acceptUrl", "http://x"));
                        assertEquals("Joan has invited you to Acme on Stocka", rendered.subject());
                }

                @Test
                @DisplayName("should resolve the invitation subject in CA with args interpolated")
                void should_resolveInvitationSubject_inCa() {
                        RenderedEmail rendered = renderer.render(
                                        "invitation",
                                        "email.invitation.subject",
                                        new Object[] { "Joan", "Acme" },
                                        CA,
                                        Map.of("inviterName", "Joan", "orgName", "Acme", "acceptUrl", "http://x"));
                        assertEquals("Joan t'ha convidat a Acme a Stocka", rendered.subject());
                }

                @Test
                @DisplayName("should not throw when locale is unknown (Spring picks a fallback bundle)")
                void should_notThrow_whenLocaleUnknown() {
                        RenderedEmail rendered = renderer.render(
                                        "invitation",
                                        "email.invitation.subject",
                                        new Object[] { "Joan", "Acme" },
                                        Locale.GERMAN,
                                        Map.of("inviterName", "Joan", "orgName", "Acme", "acceptUrl", "http://x"));
                        assertNotNull(rendered.subject());
                        assertTrue(rendered.subject().contains("Joan"));
                        assertTrue(rendered.subject().contains("Acme"));
                }

                @Test
                @DisplayName("should resolve the password-reset subject without args in ES")
                void should_resolvePasswordResetSubject_inEs() {
                        RenderedEmail rendered = renderer.render(
                                        "password-reset",
                                        "email.passwordReset.subject",
                                        null,
                                        ES,
                                        Map.of("userName", "Joan", "resetUrl", "http://x"));
                        assertEquals("Restablece tu contraseña en Stocka", rendered.subject());
                }

                @Test
                @DisplayName("should resolve the password-reset subject in EN")
                void should_resolvePasswordResetSubject_inEn() {
                        RenderedEmail rendered = renderer.render(
                                        "password-reset",
                                        "email.passwordReset.subject",
                                        null,
                                        EN,
                                        Map.of("userName", "Joan", "resetUrl", "http://x"));
                        assertEquals("Reset your Stocka password", rendered.subject());
                }

                @Test
                @DisplayName("should resolve the password-reset subject in CA")
                void should_resolvePasswordResetSubject_inCa() {
                        RenderedEmail rendered = renderer.render(
                                        "password-reset",
                                        "email.passwordReset.subject",
                                        null,
                                        CA,
                                        Map.of("userName", "Joan", "resetUrl", "http://x"));
                        assertEquals("Restableix la teva contrasenya a Stocka", rendered.subject());
                }
        }

        // -------------------------------------------------------------------------
        // invitation template
        // -------------------------------------------------------------------------

        @Nested
        @DisplayName("invitation template")
        class Invitation {

                @Test
                @DisplayName("should render with all variables interpolated in ES")
                void should_renderInvitation_inEs() {
                        RenderedEmail rendered = renderer.render(
                                        "invitation",
                                        "email.invitation.subject",
                                        new Object[] { "Joan", "Acme Corp" },
                                        ES,
                                        Map.of(
                                                        "inviterName", "Joan",
                                                        "orgName", "Acme Corp",
                                                        "acceptUrl", "https://app/invitations/abc123"));

                        assertNotNull(rendered.htmlBody());
                        assertTrue(rendered.htmlBody().contains("Joan"));
                        assertTrue(rendered.htmlBody().contains("Acme Corp"));
                        assertTrue(rendered.htmlBody().contains("https://app/invitations/abc123"));
                        assertTrue(rendered.htmlBody().contains("Te han invitado a"),
                                        "ES title should be present");
                        assertTrue(rendered.htmlBody().contains("Aceptar invitación"),
                                        "ES CTA button label should be present");
                }

                @Test
                @DisplayName("should render with all variables interpolated in EN")
                void should_renderInvitation_inEn() {
                        RenderedEmail rendered = renderer.render(
                                        "invitation",
                                        "email.invitation.subject",
                                        new Object[] { "Joan", "Acme Corp" },
                                        EN,
                                        Map.of(
                                                        "inviterName", "Joan",
                                                        "orgName", "Acme Corp",
                                                        "acceptUrl", "https://app/invitations/abc123"));

                        assertNotNull(rendered.htmlBody());
                        // th:text escapes the ' to &#39;; match a substring without the apostrophe.
                        assertTrue(rendered.htmlBody().contains("been invited to"),
                                        "EN title should be present: " + rendered.htmlBody());
                        assertTrue(rendered.htmlBody().contains("Accept invitation"),
                                        "EN CTA button label should be present");
                        assertTrue(rendered.htmlBody().contains("Acme Corp"));
                }

                @Test
                @DisplayName("should render with all variables interpolated in CA")
                void should_renderInvitation_inCa() {
                        RenderedEmail rendered = renderer.render(
                                        "invitation",
                                        "email.invitation.subject",
                                        new Object[] { "Joan", "Acme Corp" },
                                        CA,
                                        Map.of(
                                                        "inviterName", "Joan",
                                                        "orgName", "Acme Corp",
                                                        "acceptUrl", "https://app/invitations/abc123"));

                        assertNotNull(rendered.htmlBody());
                        // th:text escapes the ' to &#39;; match a substring without the apostrophe.
                        assertTrue(rendered.htmlBody().contains("han convidat a"),
                                        "CA title should be present: " + rendered.htmlBody());
                        assertTrue(rendered.htmlBody().contains("Acceptar invitació"),
                                        "CA CTA button label should be present");
                        assertTrue(rendered.htmlBody().contains("Acme Corp"));
                }

                @Test
                @DisplayName("should not throw when variables are missing (any locale)")
                void should_notThrow_when_variablesMissing() {
                        RenderedEmail rendered = renderer.render(
                                        "invitation",
                                        "email.invitation.subject",
                                        new Object[] { "", "" },
                                        ES,
                                        new HashMap<>());
                        assertNotNull(rendered.htmlBody());
                }
        }

        // -------------------------------------------------------------------------
        // password-reset template
        // -------------------------------------------------------------------------

        @Nested
        @DisplayName("password-reset template")
        class PasswordReset {

                @Test
                @DisplayName("should render with userName and resetUrl interpolated in ES")
                void should_renderPasswordReset_inEs() {
                        RenderedEmail rendered = renderer.render(
                                        "password-reset",
                                        "email.passwordReset.subject",
                                        null,
                                        ES,
                                        Map.of(
                                                        "userName", "Joan",
                                                        "resetUrl", "https://app/restablecer-password?token=xyz789"));

                        assertNotNull(rendered.htmlBody());
                        assertTrue(rendered.htmlBody().contains("Joan"));
                        assertTrue(rendered.htmlBody().contains("https://app/restablecer-password?token=xyz789"));
                        assertTrue(rendered.htmlBody().contains("Restablece tu contraseña"),
                                        "ES title should be present");
                        assertTrue(rendered.htmlBody().contains("Restablecer contraseña"),
                                        "ES CTA button label should be present");
                }

                @Test
                @DisplayName("should render with userName and resetUrl interpolated in EN")
                void should_renderPasswordReset_inEn() {
                        RenderedEmail rendered = renderer.render(
                                        "password-reset",
                                        "email.passwordReset.subject",
                                        null,
                                        EN,
                                        Map.of(
                                                        "userName", "Joan",
                                                        "resetUrl", "https://app/restablecer-password?token=xyz789"));

                        assertNotNull(rendered.htmlBody());
                        assertTrue(rendered.htmlBody().contains("Reset your password"),
                                        "EN title should be present");
                        assertTrue(rendered.htmlBody().contains("Reset password"),
                                        "EN CTA button label should be present");
                        assertTrue(rendered.htmlBody().contains("Joan"));
                }

                @Test
                @DisplayName("should render with userName and resetUrl interpolated in CA")
                void should_renderPasswordReset_inCa() {
                        RenderedEmail rendered = renderer.render(
                                        "password-reset",
                                        "email.passwordReset.subject",
                                        null,
                                        CA,
                                        Map.of(
                                                        "userName", "Joan",
                                                        "resetUrl", "https://app/restablecer-password?token=xyz789"));

                        assertNotNull(rendered.htmlBody());
                        assertTrue(rendered.htmlBody().contains("Restableix la teva contrasenya"),
                                        "CA title should be present");
                        assertTrue(rendered.htmlBody().contains("Restablir contrasenya"),
                                        "CA CTA button label should be present");
                        assertTrue(rendered.htmlBody().contains("Joan"));
                }

                @Test
                @DisplayName("should include the CTA button anchor pointing to the resetUrl")
                void should_renderCtaButton_pointingToResetUrl() {
                        RenderedEmail rendered = renderer.render(
                                        "password-reset",
                                        "email.passwordReset.subject",
                                        null,
                                        ES,
                                        Map.of(
                                                        "userName", "Joan",
                                                        "resetUrl", "https://app/restablecer-password?token=abc"));

                        assertTrue(rendered.htmlBody().contains("href=\"https://app/restablecer-password?token=abc\""),
                                        "expected CTA anchor to use resetUrl");
                }

                @Test
                @DisplayName("should mention the 30-minute TTL in ES")
                void should_mentionTtl_inEs() {
                        RenderedEmail rendered = renderer.render(
                                        "password-reset",
                                        "email.passwordReset.subject",
                                        null,
                                        ES,
                                        Map.of("userName", "Joan", "resetUrl", "http://x"));
                        assertTrue(rendered.htmlBody().contains("30 minutos"),
                                        "ES body should mention 30 minutos: " + rendered.htmlBody());
                }

                @Test
                @DisplayName("should mention the 30-minute TTL in EN")
                void should_mentionTtl_inEn() {
                        RenderedEmail rendered = renderer.render(
                                        "password-reset",
                                        "email.passwordReset.subject",
                                        null,
                                        EN,
                                        Map.of("userName", "Joan", "resetUrl", "http://x"));
                        assertTrue(rendered.htmlBody().contains("30 minutes"),
                                        "EN body should mention 30 minutes: " + rendered.htmlBody());
                }

                @Test
                @DisplayName("should mention the 30-minute TTL in CA")
                void should_mentionTtl_inCa() {
                        RenderedEmail rendered = renderer.render(
                                        "password-reset",
                                        "email.passwordReset.subject",
                                        null,
                                        CA,
                                        Map.of("userName", "Joan", "resetUrl", "http://x"));
                        assertTrue(rendered.htmlBody().contains("30 minuts"),
                                        "CA body should mention 30 minuts: " + rendered.htmlBody());
                }

                @Test
                @DisplayName("should not throw when variables are missing (any locale)")
                void should_notThrow_when_variablesMissing() {
                        RenderedEmail rendered = renderer.render(
                                        "password-reset",
                                        "email.passwordReset.subject",
                                        null,
                                        ES,
                                        new HashMap<>());
                        assertNotNull(rendered.htmlBody());
                }
        }

        // -------------------------------------------------------------------------
        // shared layout (header + footer)
        // -------------------------------------------------------------------------

        @Nested
        @DisplayName("shared layout")
        class SharedLayout {

                @Test
                @DisplayName("invitation footer in ES should use the generic 'cuenta en Stocka' wording")
                void invitation_should_useGenericFooter_inEs() {
                        RenderedEmail rendered = renderer.render(
                                        "invitation",
                                        "email.invitation.subject",
                                        new Object[] { "i", "o" },
                                        ES,
                                        Map.of("inviterName", "i", "orgName", "o", "acceptUrl", "u"));
                        assertTrue(rendered.htmlBody()
                                        .contains("Recibiste este email porque tienes una cuenta en Stocka"),
                                        "ES footer should contain the generic wording");
                }

                @Test
                @DisplayName("invitation footer in EN should use the English equivalent")
                void invitation_should_useGenericFooter_inEn() {
                        RenderedEmail rendered = renderer.render(
                                        "invitation",
                                        "email.invitation.subject",
                                        new Object[] { "i", "o" },
                                        EN,
                                        Map.of("inviterName", "i", "orgName", "o", "acceptUrl", "u"));
                        // Thymeleaf escapes the apostrophe to &#39; inside th:text (vs th:utext) —
                        // match a substring without it.
                        assertTrue(rendered.htmlBody()
                                        .contains("receiving this email because you have a Stocka account"),
                                        "EN footer should contain the English wording: " + rendered.htmlBody());
                }

                @Test
                @DisplayName("invitation footer in CA should use the Catalan equivalent")
                void invitation_should_useGenericFooter_inCa() {
                        RenderedEmail rendered = renderer.render(
                                        "invitation",
                                        "email.invitation.subject",
                                        new Object[] { "i", "o" },
                                        CA,
                                        Map.of("inviterName", "i", "orgName", "o", "acceptUrl", "u"));
                        assertTrue(rendered.htmlBody()
                                        .contains("Has rebut aquest correu perquè tens un compte a Stocka"),
                                        "CA footer should contain the Catalan wording: " + rendered.htmlBody());
                }

                @Test
                @DisplayName("password-reset footer in ES should use the same generic wording")
                void passwordReset_should_useGenericFooter_inEs() {
                        RenderedEmail rendered = renderer.render(
                                        "password-reset",
                                        "email.passwordReset.subject",
                                        null,
                                        ES,
                                        Map.of("userName", "Joan", "resetUrl", "http://x"));
                        assertTrue(rendered.htmlBody()
                                        .contains("Recibiste este email porque tienes una cuenta en Stocka"),
                                        "footer should be the generic shared wording");
                }

                @Test
                @DisplayName("layout header should still display the Stocka brand")
                void layout_should_renderHeader() {
                        RenderedEmail rendered = renderer.render(
                                        "password-reset",
                                        "email.passwordReset.subject",
                                        null,
                                        ES,
                                        Map.of("userName", "Joan", "resetUrl", "http://x"));

                        assertTrue(rendered.htmlBody().contains("background-color:#111827"),
                                        "header bar (dark background) should be rendered");
                        assertTrue(rendered.htmlBody().contains("Stocka"),
                                        "Stocka brand label should appear somewhere in the rendered email");
                }
        }

        // -------------------------------------------------------------------------
        // XSS protection — user-controlled variables must be HTML-escaped so that
        // a malicious display name cannot inject tags into the rendered email.
        // -------------------------------------------------------------------------

        @Nested
        @DisplayName("XSS protection")
        class XssProtection {

                private static final String XSS_PAYLOAD = "<script>alert(1)</script>";
                private static final String ESCAPED_OPEN = "&lt;script&gt;";
                private static final String ESCAPED_CLOSE = "&lt;/script&gt;";

                @Test
                @DisplayName("invitation should escape inviterName and orgName to prevent XSS")
                void invitation_should_escapeUserData() {
                        RenderedEmail rendered = renderer.render(
                                        "invitation",
                                        "email.invitation.subject",
                                        new Object[] { XSS_PAYLOAD, XSS_PAYLOAD },
                                        ES,
                                        Map.of(
                                                        "inviterName", XSS_PAYLOAD,
                                                        "orgName", XSS_PAYLOAD,
                                                        "acceptUrl", "https://app/x"));

                        assertFalse(rendered.htmlBody().contains(XSS_PAYLOAD),
                                        "raw <script> tag must not appear in rendered HTML: "
                                                        + rendered.htmlBody());
                        assertTrue(rendered.htmlBody().contains(ESCAPED_OPEN),
                                        "user data should be HTML-escaped (&lt;script&gt;): "
                                                        + rendered.htmlBody());
                        assertTrue(rendered.htmlBody().contains(ESCAPED_CLOSE),
                                        "user data should be HTML-escaped (&lt;/script&gt;): "
                                                        + rendered.htmlBody());
                }

                @Test
                @DisplayName("password-reset should escape userName to prevent XSS")
                void passwordReset_should_escapeUserData() {
                        RenderedEmail rendered = renderer.render(
                                        "password-reset",
                                        "email.passwordReset.subject",
                                        null,
                                        ES,
                                        Map.of(
                                                        "userName", XSS_PAYLOAD,
                                                        "resetUrl", "https://app/x"));

                        assertFalse(rendered.htmlBody().contains(XSS_PAYLOAD),
                                        "raw <script> tag must not appear in rendered HTML: "
                                                        + rendered.htmlBody());
                        assertTrue(rendered.htmlBody().contains(ESCAPED_OPEN),
                                        "userName should be HTML-escaped (&lt;script&gt;): "
                                                        + rendered.htmlBody());
                }

                @Test
                @DisplayName("email-verification should escape userName to prevent XSS")
                void emailVerification_should_escapeUserData() {
                        RenderedEmail rendered = renderer.render(
                                        "email-verification",
                                        "email.verification.subject",
                                        null,
                                        ES,
                                        Map.of(
                                                        "userName", XSS_PAYLOAD,
                                                        "verifyUrl", "https://app/x"));

                        assertFalse(rendered.htmlBody().contains(XSS_PAYLOAD),
                                        "raw <script> tag must not appear in rendered HTML: "
                                                        + rendered.htmlBody());
                        assertTrue(rendered.htmlBody().contains(ESCAPED_OPEN),
                                        "userName should be HTML-escaped (&lt;script&gt;): "
                                                        + rendered.htmlBody());
                }
        }
}
