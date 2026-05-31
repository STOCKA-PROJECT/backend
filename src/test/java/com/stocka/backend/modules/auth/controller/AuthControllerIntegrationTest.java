package com.stocka.backend.modules.auth.controller;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocka.backend.modules.security.repository.InvalidatedTokenRepository;

@SpringBootTest
@DisplayName("Auth endpoints (integration)")
class AuthControllerIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private InvalidatedTokenRepository invalidatedTokenRepository;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private MockMvc mockMvc;

    private static final String ADMIN_EMAIL    = "joanmartorellcoll03@gmail.com";
    private static final String ADMIN_PASSWORD = "12345678";

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        com.stocka.backend.modules.organizations.IntegrationTestSupport.cleanDatabase(jdbcTemplate);
    }

    private String loginAndGetToken(String email, String password) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "email", email,
                "password", password
        ));
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        Map<?, ?> response = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        return (String) response.get("accessToken");
    }

    // -------------------------------------------------------------------------
    // POST /auth/signup
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("POST /auth/signup")
    class Signup {

        private static final Map<String, String> VALID_PAYLOAD = Map.of(
                "name",           "Test",
                "lastName",       "User",
                "username",       "testuser",
                "email",          "test@test.com",
                "password",       "password123",
                "repeatPassword", "password123"
        );

        @Test
        @DisplayName("200 — should register and return the user without password (emailVerified=false)")
        void should_return200_when_inputIsValid() throws Exception {
            mockMvc.perform(post("/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(VALID_PAYLOAD)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.email").value("test@test.com"))
                    .andExpect(jsonPath("$.name").value("Test"))
                    .andExpect(jsonPath("$.lastName").value("User"))
                    .andExpect(jsonPath("$.username").value("testuser"))
                    .andExpect(jsonPath("$.role").value("USER"))
                    .andExpect(jsonPath("$.language").value("ES"))
                    .andExpect(jsonPath("$.emailVerified").value(false))
                    .andExpect(jsonPath("$.password").doesNotExist())
                    .andExpect(jsonPath("$.passwordChangedAt").doesNotExist())
                    .andExpect(jsonPath("$.deletedAt").doesNotExist())
                    .andExpect(jsonPath("$.authorities").doesNotExist());
        }

        @Test
        @DisplayName("400 — should reject registration when passwords do not match")
        void should_return400_when_passwordsDoNotMatch() throws Exception {
            Map<String, String> payload = Map.of(
                    "name", "Test", "lastName", "User", "username", "testuser",
                    "email", "test@test.com", "password", "password123", "repeatPassword", "different"
            );

            mockMvc.perform(post("/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(payload)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("409 — should reject registration when email is already in use")
        void should_return409_when_emailAlreadyExists() throws Exception {
            mockMvc.perform(post("/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(VALID_PAYLOAD)))
                    .andExpect(status().isOk());

            Map<String, String> duplicateEmail = Map.of(
                    "name", "Other", "lastName", "User", "username", "other",
                    "email", "test@test.com",
                    "password", "password123", "repeatPassword", "password123"
            );
            mockMvc.perform(post("/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(duplicateEmail)))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("409 — should reject registration when username is already taken")
        void should_return409_when_usernameAlreadyExists() throws Exception {
            mockMvc.perform(post("/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(VALID_PAYLOAD)))
                    .andExpect(status().isOk());

            Map<String, String> duplicateUsername = Map.of(
                    "name", "Other", "lastName", "User", "username", "testuser",
                    "email", "other@test.com",
                    "password", "password123", "repeatPassword", "password123"
            );
            mockMvc.perform(post("/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(duplicateUsername)))
                    .andExpect(status().isConflict());
        }

        // ----- language -----

        @Test
        @DisplayName("200 — defaults language to ES when not provided in body")
        void should_defaultLanguageToEs_when_notProvided() throws Exception {
            mockMvc.perform(post("/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(VALID_PAYLOAD)))
                    .andExpect(status().isOk());

            String stored = jdbcTemplate.queryForObject(
                    "SELECT language FROM users WHERE email = ?",
                    String.class, "test@test.com");
            org.junit.jupiter.api.Assertions.assertEquals("ES", stored);
        }

        @Test
        @DisplayName("200 — persists language ES when provided explicitly")
        void should_persistLanguageEs_when_provided() throws Exception {
            Map<String, String> payload = new HashMap<>(VALID_PAYLOAD);
            payload.put("language", "ES");
            mockMvc.perform(post("/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(payload)))
                    .andExpect(status().isOk());

            String stored = jdbcTemplate.queryForObject(
                    "SELECT language FROM users WHERE email = ?",
                    String.class, "test@test.com");
            org.junit.jupiter.api.Assertions.assertEquals("ES", stored);
        }

        @Test
        @DisplayName("200 — persists language EN when provided")
        void should_persistLanguageEn_when_provided() throws Exception {
            Map<String, String> payload = new HashMap<>(VALID_PAYLOAD);
            payload.put("language", "EN");
            mockMvc.perform(post("/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(payload)))
                    .andExpect(status().isOk());

            String stored = jdbcTemplate.queryForObject(
                    "SELECT language FROM users WHERE email = ?",
                    String.class, "test@test.com");
            org.junit.jupiter.api.Assertions.assertEquals("EN", stored);
        }

        @Test
        @DisplayName("200 — persists language CA when provided")
        void should_persistLanguageCa_when_provided() throws Exception {
            Map<String, String> payload = new HashMap<>(VALID_PAYLOAD);
            payload.put("language", "CA");
            mockMvc.perform(post("/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(payload)))
                    .andExpect(status().isOk());

            String stored = jdbcTemplate.queryForObject(
                    "SELECT language FROM users WHERE email = ?",
                    String.class, "test@test.com");
            org.junit.jupiter.api.Assertions.assertEquals("CA", stored);
        }

        @Test
        @DisplayName("200 — accepts lowercase language code 'en' and persists as EN")
        void should_acceptLowercaseLanguage_andPersistUppercase() throws Exception {
            Map<String, String> payload = new HashMap<>(VALID_PAYLOAD);
            payload.put("language", "en");
            mockMvc.perform(post("/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(payload)))
                    .andExpect(status().isOk());

            String stored = jdbcTemplate.queryForObject(
                    "SELECT language FROM users WHERE email = ?",
                    String.class, "test@test.com");
            org.junit.jupiter.api.Assertions.assertEquals("EN", stored);
        }

        @Test
        @DisplayName("200 — defaults language to ES when blank string is provided")
        void should_defaultLanguageToEs_when_blank() throws Exception {
            Map<String, String> payload = new HashMap<>(VALID_PAYLOAD);
            payload.put("language", "   ");
            mockMvc.perform(post("/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(payload)))
                    .andExpect(status().isOk());

            String stored = jdbcTemplate.queryForObject(
                    "SELECT language FROM users WHERE email = ?",
                    String.class, "test@test.com");
            org.junit.jupiter.api.Assertions.assertEquals("ES", stored);
        }

        @Test
        @DisplayName("400 — rejects an invalid language code")
        void should_return400_when_invalidLanguage() throws Exception {
            Map<String, String> payload = new HashMap<>(VALID_PAYLOAD);
            payload.put("language", "XX");
            mockMvc.perform(post("/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(payload)))
                    .andExpect(status().isBadRequest());
        }
    }

    // -------------------------------------------------------------------------
    // POST /auth/login
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("POST /auth/login")
    class Login {

        @Test
        @DisplayName("200 — should return JWT token and user when credentials are valid")
        void should_return200WithToken_when_credentialsAreValid() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of(
                    "email", ADMIN_EMAIL,
                    "password", ADMIN_PASSWORD
            ));

            mockMvc.perform(post("/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken").isNotEmpty())
                    .andExpect(jsonPath("$.expiresIn").isNumber())
                    .andExpect(jsonPath("$.user.email").value(ADMIN_EMAIL))
                    .andExpect(jsonPath("$.user.role").value("ADMIN"))
                    .andExpect(jsonPath("$.user.password").doesNotExist())
                    .andExpect(jsonPath("$.user.passwordChangedAt").doesNotExist())
                    .andExpect(jsonPath("$.user.deletedAt").doesNotExist())
                    .andExpect(jsonPath("$.user.authorities").doesNotExist());
        }

        @Test
        @DisplayName("401 — should reject login when password is wrong")
        void should_return401_when_passwordIsWrong() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of(
                    "email", ADMIN_EMAIL,
                    "password", "wrongpassword"
            ));

            mockMvc.perform(post("/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("401 — should reject login when user does not exist")
        void should_return401_when_userNotFound() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of(
                    "email", "nobody@test.com",
                    "password", "password123"
            ));

            mockMvc.perform(post("/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("403 — should reject login when email is not verified (signup default)")
        void should_return403_when_emailNotVerified() throws Exception {
            // signup leaves the user with emailVerified=false until the verification link is clicked
            mockMvc.perform(post("/auth/signup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of(
                            "name", "Test", "lastName", "User", "username", "testuser",
                            "email", "unverified@test.com",
                            "password", "password123", "repeatPassword", "password123"
                    ))))
                    .andExpect(status().isOk());

            String body = objectMapper.writeValueAsString(Map.of(
                    "email", "unverified@test.com",
                    "password", "password123"
            ));

            mockMvc.perform(post("/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isForbidden());
        }
    }

    // -------------------------------------------------------------------------
    // POST /auth/logout
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("POST /auth/logout")
    class Logout {

        @Test
        @DisplayName("204 — should invalidate a valid token")
        void should_return204_when_tokenIsValid() throws Exception {
            String token = loginAndGetToken(ADMIN_EMAIL, ADMIN_PASSWORD);

            mockMvc.perform(post("/auth/logout")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("401 — subsequent requests with the same token should be rejected after logout")
        void should_rejectSubsequentRequests_after_logout() throws Exception {
            String token = loginAndGetToken(ADMIN_EMAIL, ADMIN_PASSWORD);

            mockMvc.perform(post("/auth/logout")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNoContent());

            mockMvc.perform(get("/users/me")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("400 — should reject when Authorization header is missing")
        void should_return400_when_authorizationHeaderMissing() throws Exception {
            mockMvc.perform(post("/auth/logout"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("400 — should reject when Authorization header is not a Bearer token")
        void should_return400_when_headerIsNotBearerToken() throws Exception {
            mockMvc.perform(post("/auth/logout")
                            .header("Authorization", "Basic dXNlcjpwYXNz"))
                    .andExpect(status().isBadRequest());
        }
    }

    // -------------------------------------------------------------------------
    // GET /auth/check-username
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("GET /auth/check-username")
    class CheckUsername {

        @Test
        @DisplayName("200 — returns available=true for a free, valid username")
        void should_returnAvailable_when_usernameIsFree() throws Exception {
            mockMvc.perform(get("/auth/check-username").param("username", "freshuser"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.available").value(true))
                    .andExpect(jsonPath("$.reason").doesNotExist());
        }

        @Test
        @DisplayName("200 — returns available=false / reason=TAKEN when the username already exists")
        void should_returnTaken_when_usernameAlreadyExists() throws Exception {
            Map<String, String> payload = Map.of(
                    "name", "Test", "lastName", "User", "username", "alreadytaken",
                    "email", "taken@test.com", "password", "password123", "repeatPassword", "password123"
            );
            mockMvc.perform(post("/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(payload)))
                    .andExpect(status().isOk());

            mockMvc.perform(get("/auth/check-username").param("username", "alreadytaken"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.available").value(false))
                    .andExpect(jsonPath("$.reason").value("TAKEN"));
        }

        @Test
        @DisplayName("200 — returns reason=RESERVED for reserved usernames")
        void should_returnReserved_when_usernameIsReserved() throws Exception {
            mockMvc.perform(get("/auth/check-username").param("username", "admin"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.available").value(false))
                    .andExpect(jsonPath("$.reason").value("RESERVED"));
        }

        @Test
        @DisplayName("200 — returns reason=INVALID_FORMAT for malformed usernames")
        void should_returnInvalidFormat_when_usernameMalformed() throws Exception {
            mockMvc.perform(get("/auth/check-username").param("username", "Joan-Test"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.available").value(false))
                    .andExpect(jsonPath("$.reason").value("INVALID_FORMAT"));
        }

        @Test
        @DisplayName("200 — endpoint is publicly accessible (no auth)")
        void should_bePublic() throws Exception {
            mockMvc.perform(get("/auth/check-username").param("username", "anyone"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("200 — TAKEN when username is in another active user's history")
        void should_returnTaken_when_usernameInActiveUserHistory() throws Exception {
            // Sign up "oldname", verify email, rename to "newname". "oldname" is now history of
            // an active user → must remain unavailable for anyone else.
            signup("first@test.com", "oldname");
            jdbcTemplate.update("UPDATE users SET email_verified = TRUE WHERE email = ?", "first@test.com");
            String token = loginAndGetToken("first@test.com", "password123");

            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/users/me")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("username", "newname"))))
                    .andExpect(status().isOk());

            mockMvc.perform(get("/auth/check-username").param("username", "oldname"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.available").value(false))
                    .andExpect(jsonPath("$.reason").value("TAKEN"));
        }

        @Test
        @DisplayName("200 — username released when its owner is soft-deleted")
        void should_returnAvailable_after_ownerSoftDelete() throws Exception {
            signup("temp@test.com", "tempuser");
            jdbcTemplate.update("UPDATE users SET email_verified = TRUE WHERE email = ?", "temp@test.com");
            String token = loginAndGetToken("temp@test.com", "password123");

            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .delete("/users/me")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNoContent());

            mockMvc.perform(get("/auth/check-username").param("username", "tempuser"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.available").value(true));
        }

        private void signup(String email, String username) throws Exception {
            Map<String, String> payload = Map.of(
                    "name", "Test", "lastName", "User", "username", username,
                    "email", email, "password", "password123", "repeatPassword", "password123"
            );
            mockMvc.perform(post("/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(payload)))
                    .andExpect(status().isOk());
        }
    }

    // -------------------------------------------------------------------------
    // Username recovery: profile rename revert and reuse after deletion
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Username recovery")
    class UsernameRecovery {

        @Test
        @DisplayName("user can revert to a previous username from own history")
        void user_canRevertToOwnPreviousUsername() throws Exception {
            signup("user@test.com", "alpha");
            jdbcTemplate.update("UPDATE users SET email_verified = TRUE WHERE email = ?", "user@test.com");
            String token = loginAndGetToken("user@test.com", "password123");

            // alpha → beta, then beta → alpha (own history recovery).
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/users/me")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("username", "beta"))))
                    .andExpect(status().isOk());
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/users/me")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("username", "alpha"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.username").value("alpha"));
        }

        @Test
        @DisplayName("another user cannot claim a username from an active user's history")
        void otherUserCannotClaim_activeHistory() throws Exception {
            signup("first@test.com", "shared");
            jdbcTemplate.update("UPDATE users SET email_verified = TRUE WHERE email = ?", "first@test.com");
            String token = loginAndGetToken("first@test.com", "password123");

            // first renames to free "shared" only on the user-facing username (history is set).
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/users/me")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("username", "firstnew"))))
                    .andExpect(status().isOk());

            // A different user trying to sign up with "shared" must be rejected.
            Map<String, String> payload = Map.of(
                    "name", "Other", "lastName", "User", "username", "shared",
                    "email", "second@test.com", "password", "password123", "repeatPassword", "password123"
            );
            mockMvc.perform(post("/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(payload)))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("after soft-delete the username is available again")
        void usernameClaimableAfterSoftDelete() throws Exception {
            signup("temp@test.com", "ghost");
            jdbcTemplate.update("UPDATE users SET email_verified = TRUE WHERE email = ?", "temp@test.com");
            String token = loginAndGetToken("temp@test.com", "password123");

            // Seed history so the cleanup branch is exercised.
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/users/me")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("username", "ghostnew"))))
                    .andExpect(status().isOk());
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .delete("/users/me")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNoContent());

            // A brand new user must be able to claim the now-released username.
            Map<String, String> payload = Map.of(
                    "name", "New", "lastName", "User", "username", "ghost",
                    "email", "new@test.com", "password", "password123", "repeatPassword", "password123"
            );
            mockMvc.perform(post("/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(payload)))
                    .andExpect(status().isOk());
        }

        private void signup(String email, String username) throws Exception {
            Map<String, String> payload = Map.of(
                    "name", "Test", "lastName", "User", "username", username,
                    "email", email, "password", "password123", "repeatPassword", "password123"
            );
            mockMvc.perform(post("/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(payload)))
                    .andExpect(status().isOk());
        }
    }
}
