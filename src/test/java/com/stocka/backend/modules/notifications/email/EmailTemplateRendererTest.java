package com.stocka.backend.modules.notifications.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@DisplayName("EmailTemplateRenderer")
class EmailTemplateRendererTest {

    @Autowired private EmailTemplateRenderer renderer;

    @Test
    @DisplayName("should render invitation template with all variables interpolated")
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
