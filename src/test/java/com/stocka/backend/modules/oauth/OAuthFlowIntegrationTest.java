package com.stocka.backend.modules.oauth;

import static com.stocka.backend.modules.organizations.IntegrationTestSupport.ADMIN_EMAIL;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.ADMIN_PASSWORD;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.buildMockMvc;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.login;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.WebApplicationContext;

import com.stocka.backend.modules.oauth.client.ClientMetadataDocument;
import com.stocka.backend.modules.oauth.client.ClientMetadataDocumentClient;
import com.stocka.backend.modules.oauth.client.ClientMetadataDocumentException;
import com.stocka.backend.modules.organizations.IntegrationTestSupport;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The whole OAuth 2.1 dance an MCP client performs against the backend, over MockMvc: discovery,
 * dynamic registration (or a Client ID Metadata Document), authorize with PKCE, browser login,
 * consent, code exchange, MCP calls, refresh, revocation.
 */
@SpringBootTest
@DisplayName("OAuth flow for MCP connectors (integration)")
class OAuthFlowIntegrationTest {

    static final String REDIRECT = "http://localhost:3118/callback";
    static final String RESOURCE = "http://localhost/mcp";
    static final String CIMD_URL = "https://trusted.example/oauth/client.json";

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockitoBean private ClientMetadataDocumentClient documents;

    private final JsonMapper json = JsonMapper.builder().build();
    private MockMvc mockMvc;
    private String spaToken;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = buildMockMvc(context);
        IntegrationTestSupport.cleanDatabase(jdbcTemplate);
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        spaToken = login(mockMvc, om, ADMIN_EMAIL, ADMIN_PASSWORD);
        mockMvc.perform(post("/organizations")
                        .header("Authorization", "Bearer " + spaToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Acme\",\"slug\":\"acme\"}"))
                .andExpect(status().isOk());
    }

    // ---------- helpers ----------

    record Pkce(String verifier, String challenge) {
        static Pkce generate() throws Exception {
            byte[] bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return new Pkce(verifier, Base64.getUrlEncoder().withoutPadding().encodeToString(digest));
        }
    }

    String registerClient() throws Exception {
        MvcResult result = mockMvc.perform(post("/oauth2/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"client_name\":\"Test MCP client\",\"redirect_uris\":[\"" + REDIRECT + "\"],"
                                + "\"grant_types\":[\"authorization_code\",\"refresh_token\"],"
                                + "\"token_endpoint_auth_method\":\"none\",\"application_type\":\"native\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.client_id").isString())
                .andExpect(jsonPath("$.token_endpoint_auth_method").value("none"))
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("client_id").asText();
    }

    /**
     * The authorize request of a client. Built as a {@link URI} so the parameters end up in the query
     * string exactly once (the authorization server reads GET parameters from the query string).
     */
    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authorizeRequest(
            String clientId, Pkce pkce, String scope, String resource, String redirectUri) {
        org.springframework.web.util.UriComponentsBuilder uri = org.springframework.web.util.UriComponentsBuilder
                .fromPath("/oauth2/authorize")
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

    /** Opens the authorize URL unauthenticated (saves the request) and logs in; returns the session. */
    MockHttpSession loginFor(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authorize)
            throws Exception {
        MvcResult first = mockMvc.perform(authorize).andReturn();
        assertThat(first.getResponse().getStatus())
                .as("%s | %s", first.getResponse().getErrorMessage(), first.getResponse().getContentAsString())
                .isEqualTo(302);
        assertThat(first.getResponse().getRedirectedUrl()).endsWith("/auth/connect/login");
        MockHttpSession session = (MockHttpSession) first.getRequest().getSession(false);
        assertThat(session).isNotNull();
        MvcResult loggedIn = mockMvc.perform(post("/auth/connect/login").session(session).with(csrf())
                        .param("email", ADMIN_EMAIL).param("password", ADMIN_PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        assertThat(loggedIn.getResponse().getRedirectedUrl()).contains("/oauth2/authorize?");
        return session;
    }

    /** Runs authorize → login → consent and returns the final redirect to the client. */
    String authorize(String clientId, Pkce pkce, String scope, String resource, List<String> approvedScopes)
            throws Exception {
        MockHttpSession session = loginFor(authorizeRequest(clientId, pkce, scope, resource, REDIRECT));

        MvcResult consentRedirect = mockMvc.perform(authorizeRequest(clientId, pkce, scope, resource, REDIRECT)
                        .session(session))
                .andReturn();
        assertThat(consentRedirect.getResponse().getStatus())
                .as("%s", consentRedirect.getResponse().getErrorMessage()).isEqualTo(302);
        String consentUrl = consentRedirect.getResponse().getRedirectedUrl();
        if (consentUrl.contains("error=")) {
            return consentUrl;
        }
        assertThat(consentUrl).contains("/oauth2/consent?");
        Map<String, String> consentParams = query(consentUrl);

        mockMvc.perform(get("/oauth2/consent")
                        .session(session)
                        .param("client_id", consentParams.get("client_id"))
                        .param("scope", consentParams.get("scope"))
                        .param("state", consentParams.get("state")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"scope\"")));

        var approve = post("/oauth2/authorize").session(session)
                .param("client_id", consentParams.get("client_id"))
                .param("state", consentParams.get("state"));
        for (String s : approvedScopes) {
            approve = approve.param("scope", s);
        }
        MvcResult approved = mockMvc.perform(approve).andReturn();
        assertThat(approved.getResponse().getStatus())
                .as("%s", approved.getResponse().getErrorMessage()).isEqualTo(302);
        String redirected = approved.getResponse().getRedirectedUrl();
        assertThat(redirected).startsWith(REDIRECT);
        return redirected;
    }

    JsonNode exchange(String clientId, String code, Pkce pkce, String resource) throws Exception {
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
        MvcResult result = mockMvc.perform(request).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    JsonNode mcp(String token, String method, String params) throws Exception {
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

    static Map<String, String> query(String url) {
        Map<String, String> out = new HashMap<>();
        String q = URI.create(url).getRawQuery();
        if (q == null) return out;
        for (String pair : q.split("&")) {
            int eq = pair.indexOf('=');
            String k = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
            String v = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            out.put(k, v);
        }
        return out;
    }

    static String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    static String audience(JsonNode claims) {
        JsonNode aud = claims.path("aud");
        return aud.isArray() ? aud.get(0).asText() : aud.asText();
    }

    static JsonNode claims(String jwt) {
        String payload = jwt.split("\\.")[1];
        return JsonMapper.builder().build().readTree(Base64.getUrlDecoder().decode(payload));
    }

    // ---------- tests ----------

    @Nested
    @DisplayName("discovery")
    class Discovery {

        @Test
        @DisplayName("an MCP call without token answers 401 with the resource metadata pointer")
        void unauthenticatedChallenge() throws Exception {
            mockMvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("WWW-Authenticate",
                            org.hamcrest.Matchers.containsString("resource_metadata=\"http://localhost/.well-known/oauth-protected-resource/mcp\"")))
                    .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.containsString("scope=\"read")));
        }

        @Test
        @DisplayName("the protected resource metadata names the authorization server")
        void protectedResourceMetadata() throws Exception {
            mockMvc.perform(get("/.well-known/oauth-protected-resource/mcp"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.resource").value(RESOURCE))
                    .andExpect(jsonPath("$.authorization_servers[0]").value("http://localhost"))
                    .andExpect(jsonPath("$.scopes_supported").isArray());
            mockMvc.perform(get("/.well-known/oauth-protected-resource"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.resource").value(RESOURCE));
        }

        @Test
        @DisplayName("the authorization server metadata advertises CIMD, DCR, PKCE and the scopes")
        void authorizationServerMetadata() throws Exception {
            mockMvc.perform(get("/.well-known/oauth-authorization-server"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.issuer").value("http://localhost"))
                    .andExpect(jsonPath("$.client_id_metadata_document_supported").value(true))
                    .andExpect(jsonPath("$.registration_endpoint").value("http://localhost/oauth2/register"))
                    .andExpect(jsonPath("$.code_challenge_methods_supported[0]").value("S256"))
                    .andExpect(jsonPath("$.token_endpoint_auth_methods_supported[0]").value("none"))
                    .andExpect(jsonPath("$.authorization_response_iss_parameter_supported").value(true))
                    .andExpect(jsonPath("$.scopes_supported", org.hamcrest.Matchers.hasItems("read", "delete", "offline_access")));
        }
    }

    @Nested
    @DisplayName("dynamic client registration")
    class Registration {

        @Test
        @DisplayName("rejects non-https, non-loopback redirect URIs")
        void rejectsHttpRedirect() throws Exception {
            mockMvc.perform(post("/oauth2/register").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"client_name\":\"x\",\"redirect_uris\":[\"http://evil.example/cb\"]}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_redirect_uri"));
        }

        @Test
        @DisplayName("rejects confidential clients and unsupported grants")
        void rejectsConfidential() throws Exception {
            mockMvc.perform(post("/oauth2/register").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"redirect_uris\":[\"" + REDIRECT + "\"],\"token_endpoint_auth_method\":\"client_secret_basic\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_client_metadata"));
            mockMvc.perform(post("/oauth2/register").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"redirect_uris\":[\"" + REDIRECT + "\"],\"grant_types\":[\"client_credentials\"]}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_client_metadata"));
        }

        @Test
        @DisplayName("names the client after the redirect host when no name is given")
        void defaultName() throws Exception {
            mockMvc.perform(post("/oauth2/register").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"redirect_uris\":[\"https://client.example/cb\"]}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.client_name").value("client.example"));
        }
    }

    @Nested
    @DisplayName("authorization code with PKCE")
    class CodeFlow {

        @Test
        @DisplayName("issues a token for the MCP audience and the token works against /mcp")
        void fullDance() throws Exception {
            String clientId = registerClient();
            Pkce pkce = Pkce.generate();
            String redirected = authorize(clientId, pkce, "read inventory:write delete offline_access", RESOURCE,
                    List.of("read", "inventory:write", "offline_access"));
            Map<String, String> params = query(redirected);
            assertThat(params).containsEntry("state", "xyz").containsEntry("iss", "http://localhost").containsKey("code");

            JsonNode tokens = exchange(clientId, params.get("code"), pkce, RESOURCE);
            assertThat(tokens.path("access_token").isTextual()).as(tokens.toString()).isTrue();
            assertThat(tokens.path("refresh_token").isTextual()).isTrue();
            assertThat(tokens.path("scope").asText()).contains("read").contains("inventory:write")
                    .doesNotContain("delete");

            JsonNode claims = claims(tokens.path("access_token").asText());
            assertThat(audience(claims)).isEqualTo(RESOURCE);
            assertThat(claims.path("email").asText()).isEqualTo(ADMIN_EMAIL);
            assertThat(claims.path("client_name").asText()).isEqualTo("Test MCP client");
            assertThat(claims.path("tokenVersion").asInt()).isEqualTo(1);

            String access = tokens.path("access_token").asText();
            JsonNode init = mcp(access, "initialize",
                    "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}");
            assertThat(init.path("result").path("serverInfo").path("name").asText()).isEqualTo("stocka");

            JsonNode list = mcp(access, "tools/list", "{}");
            List<String> names = new java.util.ArrayList<>();
            list.path("result").path("tools").forEach(t -> names.add(t.path("name").asText()));
            assertThat(names).contains("me");

            JsonNode me = mcp(access, "tools/call", "{\"name\":\"me\",\"arguments\":{}}");
            JsonNode result = me.path("result");
            assertThat(result.path("isError").asBoolean()).as(me.toString()).isFalse();
            JsonNode structured = result.path("structuredContent");
            assertThat(structured.path("user").path("email").asText()).isEqualTo(ADMIN_EMAIL);
            assertThat(structured.path("organizations").get(0).path("slug").asText()).isEqualTo("acme");
            assertThat(structured.path("organizations").get(0).path("role").asText()).isEqualTo("OWNER");
            assertThat(structured.path("scopes").toString()).contains("inventory:write");
        }

        @Test
        @DisplayName("a token is refused without the delete scope on a delete tool, with a hint")
        void insufficientScope() throws Exception {
            String clientId = registerClient();
            Pkce pkce = Pkce.generate();
            String redirected = authorize(clientId, pkce, "read offline_access", RESOURCE, List.of("read"));
            JsonNode tokens = exchange(clientId, query(redirected).get("code"), pkce, RESOURCE);
            assertThat(tokens.path("refresh_token").isMissingNode()).as("no offline_access → no refresh token").isTrue();
            String access = tokens.path("access_token").asText();

            JsonNode call = mcp(access, "tools/call", "{\"name\":\"location_delete\",\"arguments\":{\"org\":\"acme\",\"locationId\":1}}");
            JsonNode result = call.path("result");
            assertThat(result.path("isError").asBoolean()).as(call.toString()).isTrue();
            assertThat(result.path("structuredContent").path("error").asText()).isEqualTo("auth.insufficient_scope");
            assertThat(result.path("structuredContent").path("hint").asText()).contains("reconnect");
        }

        @Test
        @DisplayName("a wrong resource indicator is reported as invalid_target before anything else")
        void wrongResource() throws Exception {
            String clientId = registerClient();
            Pkce pkce = Pkce.generate();
            MvcResult result = mockMvc.perform(authorizeRequest(clientId, pkce, "read", "https://evil.example/mcp", REDIRECT))
                    .andReturn();
            assertThat(result.getResponse().getStatus()).isEqualTo(302);
            assertThat(result.getResponse().getRedirectedUrl()).startsWith(REDIRECT).contains("error=invalid_target");
        }

        @Test
        @DisplayName("denying the consent sends access_denied back to the client")
        void denied() throws Exception {
            String clientId = registerClient();
            Pkce pkce = Pkce.generate();
            String redirected = authorize(clientId, pkce, "read", RESOURCE, List.of());
            assertThat(redirected).startsWith(REDIRECT).contains("error=access_denied");
        }

        @Test
        @DisplayName("an unregistered redirect URI is rejected without redirecting, even before login")
        void unregisteredRedirect() throws Exception {
            String clientId = registerClient();
            Pkce pkce = Pkce.generate();
            MvcResult bad = mockMvc.perform(authorizeRequest(clientId, pkce, "read", null, "https://evil.example/cb"))
                    .andReturn();
            assertThat(bad.getResponse().getStatus()).isEqualTo(400);
            assertThat(bad.getResponse().getRedirectedUrl()).isNull();
        }

        @Test
        @DisplayName("loopback redirects match on any port")
        void loopbackAnyPort() throws Exception {
            String clientId = registerClient();
            Pkce pkce = Pkce.generate();
            // Registered with port 3118; the request uses port 51234.
            var authorize = authorizeRequest(clientId, pkce, "read", null, "http://localhost:51234/callback");
            MockHttpSession session = loginFor(authorize);
            MvcResult consent = mockMvc.perform(authorizeRequest(clientId, pkce, "read", null,
                    "http://localhost:51234/callback").session(session)).andReturn();
            assertThat(consent.getResponse().getRedirectedUrl()).contains("/oauth2/consent?");
        }
    }

    @Nested
    @DisplayName("refresh and revocation")
    class RefreshAndRevoke {

        @Test
        @DisplayName("a public client refreshes with its client_id only and the old refresh token dies")
        void refreshRotation() throws Exception {
            String clientId = registerClient();
            Pkce pkce = Pkce.generate();
            String redirected = authorize(clientId, pkce, "read offline_access", RESOURCE, List.of("read", "offline_access"));
            JsonNode tokens = exchange(clientId, query(redirected).get("code"), pkce, RESOURCE);
            String refresh = tokens.path("refresh_token").asText();

            MvcResult refreshed = mockMvc.perform(post("/oauth2/token")
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .param("grant_type", "refresh_token")
                            .param("refresh_token", refresh)
                            .param("client_id", clientId)
                            .param("resource", RESOURCE))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode next = json.readTree(refreshed.getResponse().getContentAsString());
            assertThat(next.path("access_token").asText()).isNotEqualTo(tokens.path("access_token").asText());
            assertThat(next.path("refresh_token").asText()).isNotEqualTo(refresh);

            mockMvc.perform(post("/oauth2/token")
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .param("grant_type", "refresh_token")
                            .param("refresh_token", refresh)
                            .param("client_id", clientId))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_grant"));

            JsonNode me = mcp(next.path("access_token").asText(), "tools/call", "{\"name\":\"me\",\"arguments\":{}}");
            assertThat(me.path("result").path("isError").asBoolean()).isFalse();
        }

        @Test
        @DisplayName("the user sees and revokes the connected app; its tokens stop working at once")
        void connectedApps() throws Exception {
            String clientId = registerClient();
            Pkce pkce = Pkce.generate();
            String redirected = authorize(clientId, pkce, "read offline_access", RESOURCE, List.of("read", "offline_access"));
            JsonNode tokens = exchange(clientId, query(redirected).get("code"), pkce, RESOURCE);
            String access = tokens.path("access_token").asText();

            mockMvc.perform(get("/users/me/connected-apps").header("Authorization", "Bearer " + spaToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].clientId").value(clientId))
                    .andExpect(jsonPath("$[0].name").value("Test MCP client"))
                    .andExpect(jsonPath("$[0].registeredVia").value("DCR"))
                    .andExpect(jsonPath("$[0].scopes", org.hamcrest.Matchers.hasItem("read")));

            mockMvc.perform(delete("/users/me/connected-apps").param("clientId", clientId)
                            .header("Authorization", "Bearer " + spaToken))
                    .andExpect(status().isNoContent());

            mockMvc.perform(get("/users/me/connected-apps").header("Authorization", "Bearer " + spaToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isEmpty());

            mockMvc.perform(post("/mcp").header("Authorization", "Bearer " + access)
                            .contentType(MediaType.APPLICATION_JSON).accept("application/json, text/event-stream")
                            .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.containsString("invalid_token")));

            mockMvc.perform(post("/oauth2/token")
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .param("grant_type", "refresh_token")
                            .param("refresh_token", tokens.path("refresh_token").asText())
                            .param("client_id", clientId))
                    .andExpect(status().isBadRequest());

            mockMvc.perform(delete("/users/me/connected-apps").param("clientId", "nope")
                            .header("Authorization", "Bearer " + spaToken))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("the SPA token is not accepted by /mcp and the MCP token is not accepted by the REST API")
        void tokensDoNotCross() throws Exception {
            String clientId = registerClient();
            Pkce pkce = Pkce.generate();
            String redirected = authorize(clientId, pkce, "read", RESOURCE, List.of("read"));
            String access = exchange(clientId, query(redirected).get("code"), pkce, RESOURCE).path("access_token").asText();

            mockMvc.perform(post("/mcp").header("Authorization", "Bearer " + spaToken)
                            .contentType(MediaType.APPLICATION_JSON).accept("application/json, text/event-stream")
                            .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}"))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(get("/users/me").header("Authorization", "Bearer " + access))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("Client ID Metadata Documents")
    class Cimd {

        @Test
        @DisplayName("a URL client id resolves through its document, is marked trusted and can authorize")
        void cimdFlow() throws Exception {
            when(documents.fetch(CIMD_URL)).thenReturn(new ClientMetadataDocument(CIMD_URL, "Claude Test",
                    "https://trusted.example", null, List.of("http://localhost/callback", "http://127.0.0.1/callback")));
            Pkce pkce = Pkce.generate();
            String redirected = authorize(CIMD_URL, pkce, "read offline_access", RESOURCE, List.of("read", "offline_access"));
            JsonNode tokens = exchange(CIMD_URL, query(redirected).get("code"), pkce, RESOURCE);
            assertThat(tokens.path("access_token").isTextual()).as(tokens.toString()).isTrue();
            assertThat(claims(tokens.path("access_token").asText()).path("client_name").asText()).isEqualTo("Claude Test");

            mockMvc.perform(get("/users/me/connected-apps").header("Authorization", "Bearer " + spaToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].clientId").value(CIMD_URL))
                    .andExpect(jsonPath("$[0].trusted").value(true))
                    .andExpect(jsonPath("$[0].registeredVia").value("CIMD"));

            mockMvc.perform(delete("/users/me/connected-apps").param("clientId", CIMD_URL)
                            .header("Authorization", "Bearer " + spaToken))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("an unreachable or invalid document is rejected as an unknown client")
        void cimdRejected() throws Exception {
            when(documents.fetch(anyString())).thenThrow(new ClientMetadataDocumentException(
                    ClientMetadataDocumentException.Reason.UNREACHABLE, "down"));
            Pkce pkce = Pkce.generate();
            MvcResult bad = mockMvc.perform(authorizeRequest("https://down.example/c.json", pkce, "read", null, REDIRECT))
                    .andReturn();
            assertThat(bad.getResponse().getStatus()).isEqualTo(400);
            assertThat(bad.getResponse().getRedirectedUrl()).isNull();
        }
    }

    @Nested
    @DisplayName("browser login")
    class Login {

        @Test
        @DisplayName("wrong credentials re-render the form with an error")
        void wrongPassword() throws Exception {
            mockMvc.perform(post("/auth/connect/login").with(csrf())
                            .param("email", ADMIN_EMAIL).param("password", "nope"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("alert-error")));
        }

        @Test
        @DisplayName("the login page renders")
        void loginPage() throws Exception {
            mockMvc.perform(get("/auth/connect/login"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"password\"")));
        }

        @Test
        @DisplayName("the consent page needs a session")
        void consentNeedsLogin() throws Exception {
            mockMvc.perform(get("/oauth2/consent").param("client_id", "x").param("state", "y").accept(MediaType.TEXT_HTML))
                    .andExpect(status().is3xxRedirection());
        }
    }
}
