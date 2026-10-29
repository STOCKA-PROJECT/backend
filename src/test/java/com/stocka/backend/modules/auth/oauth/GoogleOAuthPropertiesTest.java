package com.stocka.backend.modules.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("GoogleOAuthProperties")
class GoogleOAuthPropertiesTest {

    private static GoogleOAuthProperties withProfiles(boolean enabled, String... profiles) {
        GoogleOAuthProperties props = new GoogleOAuthProperties();
        props.setEnabled(enabled);
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        ReflectionTestUtils.setField(props, "environment", env);
        return props;
    }

    @Nested
    @DisplayName("validateNotEnabledInProduction")
    class ValidateNotEnabledInProduction {

        @ParameterizedTest(name = "profile {0}")
        @ValueSource(strings = {"prod", "production", "PROD-eu"})
        @DisplayName("fails fast when enabled under a prod profile")
        void should_fail_when_enabledInProd(String profile) {
            assertThatThrownBy(withProfiles(true, profile)::validateNotEnabledInProduction)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("GOOGLE_OAUTH_ENABLED=false");
        }

        @Test
        @DisplayName("accepts enabled under dev or staging")
        void should_pass_when_enabledOutsideProd() {
            assertThatCode(withProfiles(true, "dev", "staging")::validateNotEnabledInProduction)
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("accepts disabled under prod")
        void should_pass_when_disabledInProd() {
            assertThatCode(withProfiles(false, "prod")::validateNotEnabledInProduction)
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("tolerates a missing environment (plain unit construction)")
        void should_pass_when_noEnvironment() {
            GoogleOAuthProperties props = new GoogleOAuthProperties();
            props.setEnabled(true);
            assertThatCode(props::validateNotEnabledInProduction).doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("isConfigured needs client id, secret and redirect uri")
    void should_beConfigured_only_withAllCredentials() {
        GoogleOAuthProperties props = new GoogleOAuthProperties();
        assertThat(props.isConfigured()).isFalse();
        props.setClientId("id");
        props.setClientSecret("secret");
        assertThat(props.isConfigured()).isFalse();
        props.setRedirectUri("http://localhost/cb");
        assertThat(props.isConfigured()).isTrue();
    }
}
