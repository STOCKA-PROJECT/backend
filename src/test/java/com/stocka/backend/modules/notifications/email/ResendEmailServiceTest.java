package com.stocka.backend.modules.notifications.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.stocka.backend.modules.users.entity.Language;

@ExtendWith(MockitoExtension.class)
@DisplayName("ResendEmailService")
class ResendEmailServiceTest {

    @Mock private EmailTemplateRenderer renderer;
    @Mock private ResendSender sender;

    private ResendEmailService sut;

    @BeforeEach
    void setUp() {
        sut = new ResendEmailService(renderer, sender, "Stocka <no-reply@stocka.test>");
        lenient().when(renderer.render(anyString(), anyString(), any(), any(Locale.class), any()))
                .thenReturn(new RenderedEmail("subject-rendered", "<html>BODY</html>"));
    }

    @Nested
    @DisplayName("sendInvitationEmail")
    class SendInvitationEmail {

        @Test
        @DisplayName("renders the invitation template with the right key, args and variables")
        void should_renderInvitationTemplate() {
            ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
            ArgumentCaptor<Map<String, Object>> varsCaptor = templateVarsCaptor();

            sut.sendInvitationEmail("inv@test.com", "Joan", "Acme", "https://x/y", Language.ES);

            verify(renderer).render(
                    eq("invitation"),
                    eq("email.invitation.subject"),
                    argsCaptor.capture(),
                    eq(Locale.of("es")),
                    varsCaptor.capture()
            );
            assertThat(argsCaptor.getValue()).containsExactly("Joan", "Acme");
            Map<String, Object> vars = varsCaptor.getValue();
            assertThat(vars).containsEntry("inviterName", "Joan")
                    .containsEntry("orgName", "Acme")
                    .containsEntry("acceptUrl", "https://x/y");
        }

        @Test
        @DisplayName("forwards the rendered subject and body to the sender")
        void should_passRenderedContentToSender() {
            sut.sendInvitationEmail("inv@test.com", "Joan", "Acme", "https://x/y", Language.ES);

            verify(sender).send(
                    eq("Stocka <no-reply@stocka.test>"),
                    eq("inv@test.com"),
                    eq("subject-rendered"),
                    eq("<html>BODY</html>"),
                    anyString()
            );
        }

        @Test
        @DisplayName("uses the 'org-invitation/' prefix for the idempotency key")
        void should_useInvitationPrefix() {
            ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);

            sut.sendInvitationEmail("inv@test.com", "Joan", "Acme", "https://x/y", Language.ES);

            verify(sender).send(anyString(), anyString(), anyString(), anyString(), keyCaptor.capture());
            assertThat(keyCaptor.getValue()).startsWith(ResendEmailService.INVITATION_KEY_PREFIX);
        }

        @Test
        @DisplayName("derives the idempotency key from the acceptUrl (different URL → different key)")
        void should_produceDifferentKeys_forDifferentUrls() {
            ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);

            sut.sendInvitationEmail("a@x", "i", "o", "https://x/y/AAA", Language.ES);
            sut.sendInvitationEmail("a@x", "i", "o", "https://x/y/BBB", Language.ES);

            verify(sender, org.mockito.Mockito.times(2))
                    .send(anyString(), anyString(), anyString(), anyString(), keyCaptor.capture());
            assertThat(keyCaptor.getAllValues().get(0)).isNotEqualTo(keyCaptor.getAllValues().get(1));
        }

        @Test
        @DisplayName("derives the idempotency key from the acceptUrl (same URL → same key)")
        void should_produceSameKey_forSameUrl() {
            ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);

            sut.sendInvitationEmail("a@x", "i", "o", "https://x/y/SAME", Language.ES);
            sut.sendInvitationEmail("b@x", "i", "o", "https://x/y/SAME", Language.ES);

            verify(sender, org.mockito.Mockito.times(2))
                    .send(anyString(), anyString(), anyString(), anyString(), keyCaptor.capture());
            assertThat(keyCaptor.getAllValues().get(0)).isEqualTo(keyCaptor.getAllValues().get(1));
        }

        @Test
        @DisplayName("passes the EN locale when language is EN")
        void should_passLocaleEn() {
            ArgumentCaptor<Locale> localeCaptor = ArgumentCaptor.forClass(Locale.class);

            sut.sendInvitationEmail("a@x", "i", "o", "u", Language.EN);

            verify(renderer).render(anyString(), anyString(), any(), localeCaptor.capture(), any());
            assertThat(localeCaptor.getValue()).isEqualTo(Locale.ENGLISH);
        }

        @Test
        @DisplayName("passes the CA locale when language is CA")
        void should_passLocaleCa() {
            ArgumentCaptor<Locale> localeCaptor = ArgumentCaptor.forClass(Locale.class);

            sut.sendInvitationEmail("a@x", "i", "o", "u", Language.CA);

            verify(renderer).render(anyString(), anyString(), any(), localeCaptor.capture(), any());
            assertThat(localeCaptor.getValue()).isEqualTo(Locale.of("ca"));
        }

        @Test
        @DisplayName("does not propagate ResendTransientException to the caller")
        void should_swallowTransientException() {
            org.mockito.Mockito.doThrow(new ResendTransientException("retries exhausted"))
                    .when(sender).send(anyString(), anyString(), anyString(), anyString(), anyString());

            assertThatCode(() -> sut.sendInvitationEmail("a@x", "i", "o", "u", Language.ES))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("sendPasswordResetEmail")
    class SendPasswordResetEmail {

        @Test
        @DisplayName("renders the password-reset template with the right key, args and variables")
        void should_renderPasswordResetTemplate() {
            ArgumentCaptor<Map<String, Object>> varsCaptor = templateVarsCaptor();

            sut.sendPasswordResetEmail("reset@x", "Joan", "https://x/reset?token=abc", Language.ES);

            verify(renderer).render(
                    eq("password-reset"),
                    eq("email.passwordReset.subject"),
                    any(),
                    eq(Locale.of("es")),
                    varsCaptor.capture()
            );
            assertThat(varsCaptor.getValue())
                    .containsEntry("userName", "Joan")
                    .containsEntry("resetUrl", "https://x/reset?token=abc");
        }

        @Test
        @DisplayName("uses the 'password-reset/' prefix for the idempotency key")
        void should_usePasswordResetPrefix() {
            ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);

            sut.sendPasswordResetEmail("reset@x", "Joan", "https://x/reset?token=abc", Language.ES);

            verify(sender).send(anyString(), anyString(), anyString(), anyString(), keyCaptor.capture());
            assertThat(keyCaptor.getValue()).startsWith(ResendEmailService.PASSWORD_RESET_KEY_PREFIX);
        }

        @Test
        @DisplayName("derives the idempotency key from the resetUrl")
        void should_deriveKeyFromResetUrl() {
            ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);

            sut.sendPasswordResetEmail("a@x", "u", "https://x/reset?token=AAA", Language.ES);
            sut.sendPasswordResetEmail("a@x", "u", "https://x/reset?token=BBB", Language.ES);

            verify(sender, org.mockito.Mockito.times(2))
                    .send(anyString(), anyString(), anyString(), anyString(), keyCaptor.capture());
            assertThat(keyCaptor.getAllValues().get(0)).isNotEqualTo(keyCaptor.getAllValues().get(1));
        }

        @Test
        @DisplayName("does not call the renderer or sender when the language is the default (ES)")
        void should_callRenderer_evenForDefaultLanguage() {
            // sanity: default flow still triggers the sender exactly once
            sut.sendPasswordResetEmail("reset@x", "Joan", "u", Language.ES);

            verify(sender).send(anyString(), anyString(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("passes the EN locale when language is EN")
        void should_passLocaleEn() {
            ArgumentCaptor<Locale> localeCaptor = ArgumentCaptor.forClass(Locale.class);

            sut.sendPasswordResetEmail("a@x", "u", "url", Language.EN);

            verify(renderer).render(anyString(), anyString(), any(), localeCaptor.capture(), any());
            assertThat(localeCaptor.getValue()).isEqualTo(Locale.ENGLISH);
        }

        @Test
        @DisplayName("passes the CA locale when language is CA")
        void should_passLocaleCa() {
            ArgumentCaptor<Locale> localeCaptor = ArgumentCaptor.forClass(Locale.class);

            sut.sendPasswordResetEmail("a@x", "u", "url", Language.CA);

            verify(renderer).render(anyString(), anyString(), any(), localeCaptor.capture(), any());
            assertThat(localeCaptor.getValue()).isEqualTo(Locale.of("ca"));
        }

        @Test
        @DisplayName("does not propagate ResendTransientException to the caller")
        void should_swallowTransientException() {
            org.mockito.Mockito.doThrow(new ResendTransientException("retries exhausted"))
                    .when(sender).send(anyString(), anyString(), anyString(), anyString(), anyString());

            assertThatCode(() -> sut.sendPasswordResetEmail("a@x", "u", "url", Language.ES))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("from address")
    class FromAddress {

        @Test
        @DisplayName("uses the configured 'from' address for invitations")
        void should_useConfiguredFrom_forInvitations() {
            sut.sendInvitationEmail("a@x", "i", "o", "u", Language.ES);

            verify(sender).send(eq("Stocka <no-reply@stocka.test>"),
                    anyString(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("uses the configured 'from' address for password resets")
        void should_useConfiguredFrom_forPasswordResets() {
            sut.sendPasswordResetEmail("a@x", "u", "url", Language.ES);

            verify(sender).send(eq("Stocka <no-reply@stocka.test>"),
                    anyString(), anyString(), anyString(), anyString());
        }

    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<String, Object>> templateVarsCaptor() {
        return ArgumentCaptor.forClass(Map.class);
    }
}
