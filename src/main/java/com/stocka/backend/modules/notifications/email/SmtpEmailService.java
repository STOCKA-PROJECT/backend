package com.stocka.backend.modules.notifications.email;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import com.stocka.backend.modules.users.entity.Language;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

@Service
@ConditionalOnProperty(name = "app.email.provider", havingValue = "smtp")
public class SmtpEmailService implements EmailService {
    private static final Logger log = LoggerFactory.getLogger(SmtpEmailService.class);

    private final EmailTemplateRenderer renderer;
    private final JavaMailSender mailSender;
    private final String fromAddress;

    public SmtpEmailService(
            EmailTemplateRenderer renderer,
            JavaMailSender mailSender,
            @Value("${app.email.from}") String fromAddress
    ) {
        this.renderer = renderer;
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
    }

    @Override
    public void sendInvitationEmail(String to, String inviterName, String orgName, String acceptUrl, Language language) {
        RenderedEmail email = renderer.render(
                "invitation",
                "email.invitation.subject",
                new Object[]{inviterName, orgName},
                language.toLocale(),
                Map.of(
                        "inviterName", inviterName,
                        "orgName", orgName,
                        "acceptUrl", acceptUrl
                )
        );

        sendRendered(to, email);
    }

    @Override
    public void sendPasswordResetEmail(String to, String userName, String resetUrl, Language language) {
        RenderedEmail email = renderer.render(
                "password-reset",
                "email.passwordReset.subject",
                null,
                language.toLocale(),
                Map.of(
                        "userName", userName,
                        "resetUrl", resetUrl
                )
        );

        sendRendered(to, email);
    }

    private void sendRendered(String to, RenderedEmail email) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(fromAddress);
            helper.setTo(to);
            helper.setSubject(email.subject());
            helper.setText(email.htmlBody(), true);
            mailSender.send(message);
            log.info("[SMTP EMAIL] sent to {}", to);
        } catch (MessagingException e) {
            log.warn("[SMTP EMAIL] could not send to {}: {}", to, e.getMessage());
        }
    }
}
