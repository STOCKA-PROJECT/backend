package com.stocka.backend.modules.security.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Regression for the bare {@code 401 auth.unauthenticated} that masked every error the authorization
 * server sent with {@code sendError()}: Tomcat re-dispatches those to {@code /error}, which lands in
 * the REST chain, and the chain rejected the ERROR dispatch as unauthenticated.
 *
 * <p>Runs on a real server because MockMvc never performs the container's error dispatch.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("Error dispatch through the REST chain")
class ErrorDispatchIntegrationTest {

    /** Authorize request whose client does not exist: SAS answers with sendError(400). */
    private static final String UNKNOWN_CLIENT_AUTHORIZE = "/oauth2/authorize?response_type=code"
            + "&client_id=does-not-exist&redirect_uri=https%3A%2F%2Fexample.com%2Fcb&scope=read&state=s"
            + "&code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM&code_challenge_method=S256";

    @LocalServerPort private int port;

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    private HttpResponse<String> get(String path, String accept) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Accept", accept)
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Nested
    @DisplayName("errors the authorization server sends with sendError()")
    class AuthorizationServerErrors {

        @Test
        @DisplayName("browsers get the HTML error page with the OAuth message, not a 401")
        void browserGetsErrorPage() throws Exception {
            HttpResponse<String> response = get(UNKNOWN_CLIENT_AUTHORIZE, "text/html");

            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/html");
            assertThat(response.body())
                    .contains("Error 400")
                    .contains("client_id")
                    .doesNotContain("auth.unauthenticated");
        }

        @Test
        @DisplayName("API clients get the JSON error with the message")
        void apiClientGetsJson() throws Exception {
            HttpResponse<String> response = get(UNKNOWN_CLIENT_AUTHORIZE, "application/json");

            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.body()).contains("\"status\":400").contains("client_id");
        }
    }

    @Nested
    @DisplayName("direct requests to /error")
    class DirectRequests {

        @Test
        @DisplayName("still require authentication (only the container's ERROR dispatch is open)")
        void stillProtected() throws Exception {
            HttpResponse<String> response = get("/error", "application/json");

            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.body()).contains("auth.unauthenticated");
        }
    }
}
