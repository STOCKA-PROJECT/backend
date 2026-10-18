package com.stocka.backend.modules.oauth;

import static com.stocka.backend.modules.organizations.IntegrationTestSupport.ADMIN_EMAIL;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.ADMIN_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Drives the OAuth dance over MockMvc for tests that need an MCP access token: dynamic
 * registration, authorize with PKCE, browser login as the seeded admin, consent, code exchange.
 */
public final class OAuthTestClient {

    /** Redirect URI the test client registers. */
    public static final String REDIRECT = "http://localhost:3118/callback";
    /** Canonical resource of the MCP endpoint under MockMvc. */
    public static final String RESOURCE = "http://localhost/mcp";

    private final MockMvc mockMvc;
    private final JsonMapper json = JsonMapper.builder().build();

    public OAuthTestClient(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    /** PKCE verifier/challenge pair. */
    public record Pkce(String verifier, String challenge) {
        public static Pkce generate() throws Exception {
            byte[] bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return new Pkce(verifier, Base64.getUrlEncoder().withoutPadding().encodeToString(digest));
        }
    }

    /** Tokens of a completed flow. */
    public record Tokens(String accessToken, String refreshToken, String scope) {
    }

    /** Registers a public client and returns its client id. */
    public String registerClient(String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/oauth2/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"client_name\":\"" + name + "\",\"redirect_uris\":[\"" + REDIRECT + "\"],"
                                + "\"grant_types\":[\"authorization_code\",\"refresh_token\"],"
                                + "\"token_endpoint_auth_method\":\"none\",\"application_type\":\"native\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("client_id").asText();
    }

    /** Full flow as the seeded admin, approving exactly {@code approvedScopes}. */
    public Tokens connect(String clientId, String requestedScopes, List<String> approvedScopes) throws Exception {
        Pkce pkce = Pkce.generate();
        String redirected = authorize(clientId, pkce, requestedScopes, RESOURCE, approvedScopes);
        Map<String, String> params = query(redirected);
        assertThat(params).as(redirected).containsKey("code");
        JsonNode tokens = exchange(clientId, params.get("code"), pkce, RESOURCE);
        assertThat(tokens.path("access_token").isTextual()).as(tokens.toString()).isTrue();
        return new Tokens(tokens.path("access_token").asText(),
                tokens.path("refresh_token").isTextual() ? tokens.path("refresh_token").asText() : null,
                tokens.path("scope").asText());
    }

    /** Registers a client and connects it with every scope approved. */
    public Tokens connectWithAllScopes() throws Exception {
        String clientId = registerClient("Test MCP client");
        List<String> all = OAuthScope.allValues();
        return connect(clientId, String.join(" ", all), all);
    }

    public org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authorizeRequest(
            String clientId, Pkce pkce, String scope, String resource, String redirectUri) {
        UriComponentsBuilder uri = UriComponentsBuilder.fromPath("/oauth2/authorize")
                .queryParam("response_type", "code")
                .queryParam("client_id", clientId)
                .queryParam("redirect_uri", redirectUri)
                .queryParam("scope", scope)
                .queryParam("state", "xyz")
                .queryParam("code_challenge", pkce.challenge())
                .queryParam("code_challenge_method", "S256");
        if (resource != null) {
            uri.queryParam("resource", resource);
        }
        return get(uri.build().encode().toUri()).accept(MediaType.TEXT_HTML);
    }

    public MockHttpSession loginFor(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authorize)
            throws Exception {
        MvcResult first = mockMvc.perform(authorize).andReturn();
        assertThat(first.getResponse().getStatus())
                .as("%s | %s", first.getResponse().getErrorMessage(), first.getResponse().getContentAsString())
                .isEqualTo(302);
        assertThat(first.getResponse().getRedirectedUrl()).endsWith("/auth/connect/login");
        MockHttpSession session = (MockHttpSession) first.getRequest().getSession(false);
        assertThat(session).isNotNull();
        mockMvc.perform(post("/auth/connect/login").session(session).with(csrf())
                        .param("email", ADMIN_EMAIL).param("password", ADMIN_PASSWORD))
                .andExpect(status().is3xxRedirection());
        return session;
    }

    public String authorize(String clientId, Pkce pkce, String scope, String resource, List<String> approvedScopes)
            throws Exception {
        MockHttpSession session = loginFor(authorizeRequest(clientId, pkce, scope, resource, REDIRECT));
        MvcResult consentRedirect = mockMvc.perform(authorizeRequest(clientId, pkce, scope, resource, REDIRECT)
                .session(session)).andReturn();
        assertThat(consentRedirect.getResponse().getStatus())
                .as("%s", consentRedirect.getResponse().getErrorMessage()).isEqualTo(302);
        String consentUrl = consentRedirect.getResponse().getRedirectedUrl();
        if (consentUrl.contains("error=")) {
            return consentUrl;
        }
        Map<String, String> consentParams = query(consentUrl);
        mockMvc.perform(get("/oauth2/consent").session(session)
                        .param("client_id", consentParams.get("client_id"))
                        .param("scope", consentParams.get("scope"))
                        .param("state", consentParams.get("state")))
                .andExpect(status().isOk());
        var approve = post("/oauth2/authorize").session(session)
                .param("client_id", consentParams.get("client_id"))
                .param("state", consentParams.get("state"));
        for (String s : approvedScopes) {
            approve = approve.param("scope", s);
        }
        MvcResult approved = mockMvc.perform(approve).andReturn();
        assertThat(approved.getResponse().getStatus()).as("%s", approved.getResponse().getErrorMessage()).isEqualTo(302);
        return approved.getResponse().getRedirectedUrl();
    }

    public JsonNode exchange(String clientId, String code, Pkce pkce, String resource) throws Exception {
        var request = post("/oauth2/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "authorization_code")
                .param("code", code)
                .param("redirect_uri", REDIRECT)
                .param("client_id", clientId)
                .param("code_verifier", pkce.verifier());
        if (resource != null) {
            request = request.param("resource", resource);
        }
        return json.readTree(mockMvc.perform(request).andReturn().getResponse().getContentAsString());
    }

    /** One JSON-RPC call to {@code /mcp}; asserts HTTP 200 and returns the parsed response. */
    public JsonNode mcp(String token, String method, String params) throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\",\"params\":" + params + "}";
        MvcResult result = mockMvc.perform(post("/mcp")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept("application/json, text/event-stream")
                        .content(body))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        String text = result.getResponse().getContentAsString();
        if (text.startsWith("event:") || text.startsWith("data:")) {
            StringBuilder data = new StringBuilder();
            for (String line : text.split("\n")) {
                if (line.startsWith("data:")) data.append(line.substring(5).trim());
            }
            text = data.toString();
        }
        return json.readTree(text);
    }

    /** Calls a tool and returns the {@code result} node. */
    public JsonNode call(String token, String tool, String argumentsJson) throws Exception {
        JsonNode response = mcp(token, "tools/call", "{\"name\":\"" + tool + "\",\"arguments\":" + argumentsJson + "}");
        assertThat(response.has("result")).as(response.toString()).isTrue();
        return response.path("result");
    }

    /** Calls a tool expecting success and returns its structured content. */
    public JsonNode ok(String token, String tool, String argumentsJson) throws Exception {
        JsonNode result = call(token, tool, argumentsJson);
        assertThat(result.path("isError").asBoolean()).as(result.toString()).isFalse();
        return result.path("structuredContent");
    }

    /** Calls a tool expecting an error and returns its structured error. */
    public JsonNode error(String token, String tool, String argumentsJson) throws Exception {
        JsonNode result = call(token, tool, argumentsJson);
        assertThat(result.path("isError").asBoolean()).as(result.toString()).isTrue();
        return result.path("structuredContent");
    }

    public static Map<String, String> query(String url) {
        Map<String, String> out = new HashMap<>();
        String q = URI.create(url).getRawQuery();
        if (q == null) return out;
        for (String pair : q.split("&")) {
            int eq = pair.indexOf('=');
            out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    public JsonMapper json() {
        return json;
    }
}
