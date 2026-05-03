package com.stocka.backend.modules.notifications.email;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.thymeleaf.autoconfigure.ThymeleafAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.stocka.backend.modules.notifications.email.webhook.ResendWebhookController;
import com.stocka.backend.modules.notifications.email.webhook.ResendWebhookEventHandler;

/**
 * Verifies that the {@code app.email.provider} switch wires only the expected beans.
 * Runs without a full Spring Boot context to keep these tests fast and focused.
 */
@DisplayName("Resend wiring (@ConditionalOnProperty)")
class ResendEmailServiceConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    PropertyPlaceholderAutoConfiguration.class,
                    JacksonAutoConfiguration.class,
                    ThymeleafAutoConfiguration.class
            ))
            .withUserConfiguration(SupportBeans.class)
            .withUserConfiguration(ResendClientConfiguration.class)
            .withUserConfiguration(ResendSender.class, ResendEmailService.class)
            .withUserConfiguration(ResendWebhookEventHandler.class, ResendWebhookController.class)
            .withUserConfiguration(LocalEmailService.class, SmtpEmailService.class)
            .withPropertyValues(
                    "app.email.from=test@stocka.local"
            );

    @Nested
    @DisplayName("when app.email.provider=resend")
    class ResendActive {

        @Test
        @DisplayName("loads ResendEmailService and the Resend webhook controller")
        void should_loadResendBeans() {
            contextRunner
                    .withPropertyValues(
                            "app.email.provider=resend",
                            "app.email.resend.api-key=re_test_key",
                            "app.email.resend.webhook-secret=" + dummySvixSecret(),
                            "app.email.resend.max-retries=2",
                            "app.email.resend.initial-backoff-ms=10"
                    )
                    .run(context -> {
                        assertThat(context).hasSingleBean(ResendEmailService.class);
                        assertThat(context).hasSingleBean(ResendSender.class);
                        assertThat(context).hasSingleBean(ResendWebhookController.class);
                        assertThat(context).hasSingleBean(ResendWebhookEventHandler.class);
                        assertThat(context).doesNotHaveBean(LocalEmailService.class);
                        assertThat(context).doesNotHaveBean(SmtpEmailService.class);
                    });
        }

        @Test
        @DisplayName("fails to start when the API key is missing")
        void should_failStartup_whenApiKeyMissing() {
            contextRunner
                    .withPropertyValues(
                            "app.email.provider=resend",
                            "app.email.resend.webhook-secret=" + dummySvixSecret()
                    )
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .rootCause()
                                .hasMessageContaining("RESEND_API_KEY");
                    });
        }

        @Test
        @DisplayName("starts without the webhook controller when the webhook secret is empty")
        void should_skipWebhookBeans_whenSecretMissing() {
            contextRunner
                    .withPropertyValues(
                            "app.email.provider=resend",
                            "app.email.resend.api-key=re_test_key"
                            // intentionally no webhook-secret
                    )
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        // outgoing email path is still wired
                        assertThat(context).hasSingleBean(ResendEmailService.class);
                        assertThat(context).hasSingleBean(ResendSender.class);
                        // webhook stack is skipped
                        assertThat(context).doesNotHaveBean(com.svix.Webhook.class);
                        assertThat(context).doesNotHaveBean(ResendWebhookController.class);
                        assertThat(context).doesNotHaveBean(ResendWebhookEventHandler.class);
                    });
        }

        @Test
        @DisplayName("treats a placeholder without the 'whsec_' prefix as if no webhook were configured")
        void should_skipWebhookBeans_whenSecretLacksWhsecPrefix() {
            contextRunner
                    .withPropertyValues(
                            "app.email.provider=resend",
                            "app.email.resend.api-key=re_test_key",
                            "app.email.resend.webhook-secret=your-webhook-secret-here"
                    )
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context).hasSingleBean(ResendEmailService.class);
                        assertThat(context).doesNotHaveBean(com.svix.Webhook.class);
                        assertThat(context).doesNotHaveBean(ResendWebhookController.class);
                    });
        }

        @Test
        @DisplayName("fails to start when the webhook secret has the prefix but invalid base64")
        void should_failStartup_whenWebhookSecretInvalid() {
            contextRunner
                    .withPropertyValues(
                            "app.email.provider=resend",
                            "app.email.resend.api-key=re_test_key",
                            "app.email.resend.webhook-secret=whsec_not__valid__base64"
                    )
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .rootCause()
                                .isInstanceOf(IllegalArgumentException.class);
                    });
        }
    }

    @Nested
    @DisplayName("when app.email.provider=local (default in tests)")
    class LocalActive {

        @Test
        @DisplayName("loads LocalEmailService and skips Resend beans")
        void should_loadLocalAndSkipResend() {
            contextRunner
                    .withPropertyValues("app.email.provider=local")
                    .run(context -> {
                        assertThat(context).hasSingleBean(LocalEmailService.class);
                        assertThat(context).doesNotHaveBean(ResendEmailService.class);
                        assertThat(context).doesNotHaveBean(ResendSender.class);
                        assertThat(context).doesNotHaveBean(ResendWebhookController.class);
                        assertThat(context).doesNotHaveBean(ResendWebhookEventHandler.class);
                    });
        }

        @Test
        @DisplayName("loads LocalEmailService when the property is missing entirely (matchIfMissing)")
        void should_defaultToLocal_whenPropertyMissing() {
            contextRunner.run(context -> {
                assertThat(context).hasSingleBean(LocalEmailService.class);
                assertThat(context).doesNotHaveBean(ResendEmailService.class);
            });
        }
    }

    /**
     * Tiny helper config providing the dependencies that the email beans need
     * (renderer, JavaMailSender, message source) without spinning up a full Boot context.
     */
    @Configuration
    static class SupportBeans {

        @Bean
        EmailTemplateRenderer emailTemplateRenderer(
                org.thymeleaf.spring6.SpringTemplateEngine engine, MessageSource messageSource) {
            return new EmailTemplateRenderer(engine, messageSource);
        }

        @Bean
        MessageSource messageSource() {
            ResourceBundleMessageSource ms = new ResourceBundleMessageSource();
            ms.setBasename("messages");
            ms.setDefaultEncoding("UTF-8");
            return ms;
        }

        @Bean
        org.springframework.mail.javamail.JavaMailSender javaMailSender() {
            return new org.springframework.mail.javamail.JavaMailSenderImpl();
        }

        @Bean
        com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
            return new com.fasterxml.jackson.databind.ObjectMapper();
        }
    }

    /**
     * Build a dummy Svix-format secret. {@code com.svix.Webhook} expects a base64-encoded
     * value following the {@code whsec_} prefix. 32 zero bytes produces a valid prefix.
     */
    private static String dummySvixSecret() {
        byte[] zeros = new byte[32];
        return "whsec_" + java.util.Base64.getEncoder().encodeToString(zeros);
    }

}
