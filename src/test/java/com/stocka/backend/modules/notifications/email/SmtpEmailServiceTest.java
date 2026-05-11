package com.stocka.backend.modules.notifications.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Locale;
import java.util.Properties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;

import com.stocka.backend.modules.users.entity.Language;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

@ExtendWith(MockitoExtension.class)
@DisplayName("SmtpEmailService")
class SmtpEmailServiceTest {

    @Mock private EmailTemplateRenderer renderer;
    @Mock private JavaMailSender mailSender;

    private SmtpEmailService sut;
    private Session session;

    @BeforeEach
    void setUp() {
        sut = new SmtpEmailService(renderer, mailSender, "noreply@stocka.test");
        session = Session.getInstance(new Properties());
        lenient().when(mailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage(session));
        lenient().when(renderer.render(anyString(), anyString(), any(), any(Locale.class), any()))
                .thenReturn(new RenderedEmail("subject-rendered", "<html>BODY</html>"));
    }

    @Nested
    @DisplayName("CRLF header sanitization")
    class CrlfSanitization {

        @Test
        @DisplayName("strips CR/LF from the subject before it reaches the MimeMessage header")
        void should_stripCrlfFromSubject() throws Exception {
            String maliciousSubject = "Invitation from bob\r\nBcc: leak@evil.com to join Acme";
            when(renderer.render(anyString(), anyString(), any(), any(Locale.class), any()))
                    .thenReturn(new RenderedEmail(maliciousSubject, "<html/>"));
            ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);

            sut.sendInvitationEmail("user@example.com", "bob\r\nBcc: leak@evil.com", "Acme",
                    "https://x/y", Language.ES);

            verify(mailSender).send(captor.capture());
            String subject = captor.getValue().getSubject();
            assertThat(subject).doesNotContain("\r").doesNotContain("\n");
            assertThat(subject).isNotEqualTo(maliciousSubject);
        }

        @Test
        @DisplayName("does not deliver the message when the From address smuggles CR/LF")
        void should_notSend_when_fromContainsCrlf() {
            SmtpEmailService poisoned = new SmtpEmailService(renderer, mailSender,
                    "noreply@stocka.test\r\nBcc: leak@evil.com");

            poisoned.sendPasswordResetEmail("user@example.com", "Joan", "https://x", Language.ES);

            // After sanitization the From becomes "noreply@stocka.test  Bcc: leak@evil.com",
            // which is not a parseable address. MimeMessageHelper aborts before
            // mailSender.send is invoked, so the raw CRLF never reaches a header.
            verify(mailSender, never()).send(any(MimeMessage.class));
        }

        @Test
        @DisplayName("does not deliver the message when the recipient address smuggles CR/LF")
        void should_notSend_when_recipientContainsCrlf() {
            sut.sendInvitationEmail("user@example.com\r\nBcc: leak@evil.com",
                    "Joan", "Acme", "https://x/y", Language.ES);

            verify(mailSender, never()).send(any(MimeMessage.class));
        }
    }
}
