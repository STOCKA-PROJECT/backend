package com.stocka.backend.modules.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import com.stocka.backend.modules.common.error.ApiException;
import com.stocka.backend.modules.common.error.ErrorCodes;

@ExtendWith(MockitoExtension.class)
@DisplayName("GoogleOAuthService")
class GoogleOAuthServiceTest {

    @Mock
    private GoogleOAuthClient client;

    private GoogleOAuthProperties properties;
    private GoogleOAuthService sut;

    @BeforeEach
    void setUp() {
        properties = new GoogleOAuthProperties();
        properties.setClientId("id");
        properties.setClientSecret("secret");
        properties.setRedirectUri("http://localhost:3002/auth/oauth/callback");
        sut = new GoogleOAuthService(client, properties);
    }

    @Nested
    @DisplayName("when the flow is disabled")
    class Disabled {

        @BeforeEach
        void disable() {
            properties.setEnabled(false);
        }

        @Test
        @DisplayName("buildAuthorization answers 404 auth.oauth_disabled")
        void should_reject_buildAuthorization() {
            assertThatThrownBy(sut::buildAuthorization)
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                        assertThat(e.getCode()).isEqualTo(ErrorCodes.OAUTH_DISABLED);
                    });
        }

        @Test
        @DisplayName("verifyAndFetchProfile answers 404 even with credentials present")
        void should_reject_callback() {
            assertThatThrownBy(() -> sut.verifyAndFetchProfile("state", null, "code"))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.OAUTH_DISABLED));
        }
    }

    @Nested
    @DisplayName("when the flow is enabled")
    class Enabled {

        @BeforeEach
        void enable() {
            properties.setEnabled(true);
        }

        @Test
        @DisplayName("without credentials answers 503 auth.oauth_not_configured")
        void should_return503_when_unconfigured() {
            properties.setClientId("");

            assertThatThrownBy(sut::buildAuthorization)
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                        assertThat(e.getCode()).isEqualTo(ErrorCodes.OAUTH_NOT_CONFIGURED);
                    });
        }

        @Test
        @DisplayName("with credentials builds the authorize URL and the state cookie")
        void should_buildAuthorization_when_configured() {
            GoogleOAuthService.AuthorizationResult result = sut.buildAuthorization();

            assertThat(result.authorizationUrl()).startsWith(properties.getAuthorizeEndpoint());
            assertThat(result.stateCookie().getName()).isEqualTo(GoogleOAuthService.STATE_COOKIE);
        }
    }
}
