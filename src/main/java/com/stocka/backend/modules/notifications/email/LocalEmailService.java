package com.stocka.backend.modules.notifications.email;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.stocka.backend.modules.users.entity.Language;

@Service
@ConditionalOnProperty(name = "app.email.provider", havingValue = "local", matchIfMissing = true)
public class LocalEmailService implements EmailService {
    private static final Logger log = LoggerFactory.getLogger(LocalEmailService.class);
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final EmailTemplateRenderer renderer;
    private final String fromAddress;
    private final String localDir;

    public LocalEmailService(
            EmailTemplateRenderer renderer,
            @Value("${app.email.from}") String fromAddress,
            @Value("${app.email.local.dir:target/emails}") String localDir
    ) {
        this.renderer = renderer;
        this.fromAddress = fromAddress;
        this.localDir = localDir;
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

        log.info("[LOCAL EMAIL] from={} to={} subject={}", fromAddress, to, email.subject());
        writeToFile("invitation", to, email);
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

        log.info("[LOCAL EMAIL] from={} to={} subject={}", fromAddress, to, email.subject());
        writeToFile("password-reset", to, email);
    }

    private void writeToFile(String prefix, String to, RenderedEmail email) {
        try {
            Path dir = Paths.get(localDir);
            Files.createDirectories(dir);
            String safeTo = to.replaceAll("[^a-zA-Z0-9._@-]", "_");
            String filename = prefix + "-" + LocalDateTime.now().format(TIMESTAMP_FORMAT) + "-" + safeTo + ".html";
            Path file = dir.resolve(filename);
            String content = "<!-- Subject: " + email.subject() + " -->\n" + email.htmlBody();
            Files.writeString(file, content, StandardCharsets.UTF_8);
            log.info("[LOCAL EMAIL] written to {}", file.toAbsolutePath());
        } catch (IOException | RuntimeException e) {
            log.warn("[LOCAL EMAIL] could not write email file: {}", e.getMessage());
        }
    }
}
