package com.stocka.backend.modules.security.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.error.ErrorAttributeOptions.Include;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

import jakarta.servlet.RequestDispatcher;

@DisplayName("SafeErrorAttributes")
class SafeErrorAttributesTest {

    private static final String OAUTH_MESSAGE = "[invalid_request] OAuth 2.0 Parameter: client_id";

    private final SafeErrorAttributes attributes = new SafeErrorAttributes();

    private Map<String, Object> attributesFor(Integer status, String message, ErrorAttributeOptions options) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
        if (status != null) request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, status);
        if (message != null) request.setAttribute(RequestDispatcher.ERROR_MESSAGE, message);
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/oauth2/authorize");
        return attributes.getErrorAttributes(new ServletWebRequest(request), options);
    }

    @Nested
    @DisplayName("client errors (4xx)")
    class ClientErrors {

        @ParameterizedTest(name = "status {0}")
        @ValueSource(ints = {400, 401, 403, 404, 429, 499})
        @DisplayName("include the message even when the options do not ask for it")
        void messageIncluded(int status) {
            Map<String, Object> result = attributesFor(status, OAUTH_MESSAGE, ErrorAttributeOptions.defaults());
            assertThat(result).containsEntry("status", status).containsEntry("message", OAUTH_MESSAGE);
        }

        @Test
        @DisplayName("fall back to Boot's placeholder when sendError() carried no message")
        void placeholderWithoutMessage() {
            Map<String, Object> result = attributesFor(400, null, ErrorAttributeOptions.defaults());
            assertThat(result.get("message")).isEqualTo("No message available");
        }
    }

    @Nested
    @DisplayName("server errors (5xx)")
    class ServerErrors {

        @ParameterizedTest(name = "status {0}")
        @ValueSource(ints = {500, 502, 503})
        @DisplayName("never expose the message, even when the options include it")
        void messageStripped(int status) {
            Map<String, Object> result = attributesFor(status, "Could not convert [...] to -4",
                    ErrorAttributeOptions.defaults().including(Include.MESSAGE));
            assertThat(result).containsEntry("status", status).doesNotContainKey("message");
        }
    }

    @Nested
    @DisplayName("without a status code")
    class NoStatus {

        @Test
        @DisplayName("is treated as a server error and keeps the message hidden")
        void hidden() {
            Map<String, Object> result = attributesFor(null, "secret",
                    ErrorAttributeOptions.defaults().including(Include.MESSAGE));
            assertThat(result).doesNotContainKey("message");
        }
    }
}
