package com.stocka.backend.modules.users.controller;

import static com.stocka.backend.modules.organizations.IntegrationTestSupport.ADMIN_EMAIL;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.ADMIN_PASSWORD;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.buildMockMvc;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.login;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.signupAndLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocka.backend.modules.organizations.IntegrationTestSupport;

@SpringBootTest
@DisplayName("UserController (integration)")
class UserControllerIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final ObjectMapper om = new ObjectMapper();

    private MockMvc mockMvc;
    private String adminToken;
    private String userBToken;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = buildMockMvc(context);
        IntegrationTestSupport.cleanDatabase(jdbcTemplate);
        adminToken = login(mockMvc, om, ADMIN_EMAIL, ADMIN_PASSWORD);
        userBToken = signupAndLogin(mockMvc, om, "userb@test.com", "userb");
    }

    // -------------------------------------------------------------------------
    // GET /users/me
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("GET /users/me")
    class GetMe {

        @Test
        @DisplayName("200 — returns current user when authenticated")
        void should_return200_when_authenticated() throws Exception {
            mockMvc.perform(get("/users/me")
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.email").value(ADMIN_EMAIL))
                    .andExpect(jsonPath("$.password").doesNotExist());
        }

        @Test
        @DisplayName("401 — without token")
        void should_return401_when_noToken() throws Exception {
            mockMvc.perform(get("/users/me"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("401 — with invalid token")
        void should_return401_when_invalidToken() throws Exception {
            mockMvc.perform(get("/users/me")
                            .header("Authorization", "Bearer not-a-real-token"))
                    .andExpect(status().isUnauthorized());
        }
    }

    // -------------------------------------------------------------------------
    // PATCH /users/me
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("PATCH /users/me")
    class PatchMe {

        @Test
        @DisplayName("200 — patching name only leaves other fields intact")
        void should_return200_when_patchingNameOnly() throws Exception {
            mockMvc.perform(patch("/users/me")
                            .header("Authorization", "Bearer " + userBToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("name", "NuevoNombre"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("NuevoNombre"))
                    .andExpect(jsonPath("$.lastName").value("User"))
                    .andExpect(jsonPath("$.email").value("userb@test.com"))
                    .andExpect(jsonPath("$.username").value("userb"));
        }

        @Test
        @DisplayName("200 — patching lastName only leaves other fields intact")
        void should_return200_when_patchingLastNameOnly() throws Exception {
            mockMvc.perform(patch("/users/me")
                            .header("Authorization", "Bearer " + userBToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("lastName", "NuevoApellido"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Test"))
                    .andExpect(jsonPath("$.lastName").value("NuevoApellido"));
        }

        @Test
        @DisplayName("200 — patching name and lastName at once")
        void should_return200_when_patchingNameAndLastName() throws Exception {
            mockMvc.perform(patch("/users/me")
                            .header("Authorization", "Bearer " + userBToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of(
                                    "name", "Nuevo",
                                    "lastName", "Apellido"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Nuevo"))
                    .andExpect(jsonPath("$.lastName").value("Apellido"));
        }

        @Test
        @DisplayName("200 — patching email to a new value resets emailVerified to false in DB")
        void should_return200_andResetEmailVerified_when_emailChanges() throws Exception {
            mockMvc.perform(patch("/users/me")
                            .header("Authorization", "Bearer " + userBToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("email", "userb-new@test.com"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.email").value("userb-new@test.com"));

            Boolean verified = jdbcTemplate.queryForObject(
                    "SELECT email_verified FROM users WHERE email = ?",
                    Boolean.class, "userb-new@test.com");
            org.junit.jupiter.api.Assertions.assertEquals(Boolean.FALSE, verified);
        }

        @Test
        @DisplayName("200 — patching email to the same value keeps emailVerified intact")
        void should_keepEmailVerified_when_emailUnchanged() throws Exception {
            mockMvc.perform(patch("/users/me")
                            .header("Authorization", "Bearer " + userBToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("email", "userb@test.com"))))
                    .andExpect(status().isOk());

            Boolean verified = jdbcTemplate.queryForObject(
                    "SELECT email_verified FROM users WHERE email = ?",
                    Boolean.class, "userb@test.com");
            org.junit.jupiter.api.Assertions.assertEquals(Boolean.TRUE, verified);
        }

        @Test
        @DisplayName("200 — patching username to a new value")
        void should_return200_when_patchingUsername() throws Exception {
            mockMvc.perform(patch("/users/me")
                            .header("Authorization", "Bearer " + userBToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("username", "newuserb"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.username").value("newuserb"));
        }

        @Test
        @DisplayName("200 — patching username to the same value is a no-op")
        void should_return200_when_usernameUnchanged() throws Exception {
            mockMvc.perform(patch("/users/me")
                            .header("Authorization", "Bearer " + userBToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("username", "userb"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.username").value("userb"));
        }

        @Test
        @DisplayName("200 — patching all fields at once")
        void should_return200_when_patchingAllFields() throws Exception {
            mockMvc.perform(patch("/users/me")
                            .header("Authorization", "Bearer " + userBToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of(
                                    "name", "Full",
                                    "lastName", "Update",
                                    "email", "userb-full@test.com",
                                    "username", "userbfull"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Full"))
                    .andExpect(jsonPath("$.lastName").value("Update"))
                    .andExpect(jsonPath("$.email").value("userb-full@test.com"))
                    .andExpect(jsonPath("$.username").value("userbfull"));
        }

        @Test
        @DisplayName("200 — empty body is a no-op and returns the current user")
        void should_return200_when_emptyBody() throws Exception {
            mockMvc.perform(patch("/users/me")
                            .header("Authorization", "Bearer " + userBToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.email").value("userb@test.com"))
                    .andExpect(jsonPath("$.username").value("userb"));
        }

        @Test
        @DisplayName("409 — new email already belongs to another active user")
        void should_return409_when_emailTakenByOther() throws Exception {
            mockMvc.perform(patch("/users/me")
                            .header("Authorization", "Bearer " + userBToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("email", ADMIN_EMAIL))))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("409 — new username already belongs to another active user")
        void should_return409_when_usernameTakenByOther() throws Exception {
            // admin's username — fetched from DB to avoid hardcoding the seeder value.
            String adminUsername = jdbcTemplate.queryForObject(
                    "SELECT username FROM users WHERE email = ?", String.class, ADMIN_EMAIL);

            mockMvc.perform(patch("/users/me")
                            .header("Authorization", "Bearer " + userBToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("username", adminUsername))))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("200 — new email belonging to a soft-deleted user is allowed (reuse)")
        void should_allowEmailReuse_from_softDeletedUser() throws Exception {
            // Sign up userC then soft-delete them directly in DB.
            signupAndLogin(mockMvc, om, "userc@test.com", "userc");
            jdbcTemplate.update("UPDATE users SET deleted_at = NOW() WHERE email = ?", "userc@test.com");

            mockMvc.perform(patch("/users/me")
                            .header("Authorization", "Bearer " + userBToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("email", "userc@test.com"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.email").value("userc@test.com"));
        }

        @Test
        @DisplayName("401 — without token")
        void should_return401_when_noToken() throws Exception {
            mockMvc.perform(patch("/users/me")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isUnauthorized());
        }
    }

    // -------------------------------------------------------------------------
    // DELETE /users/me
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("DELETE /users/me")
    class DeleteMe {

        @Test
        @DisplayName("204 — soft-deletes user and the same token can no longer authenticate")
        void should_return204_andInvalidateUser() throws Exception {
            mockMvc.perform(delete("/users/me")
                            .header("Authorization", "Bearer " + userBToken))
                    .andExpect(status().isNoContent());

            // After soft-delete, @SQLRestriction hides the user → JWT filter cannot resolve them → 401.
            mockMvc.perform(get("/users/me")
                            .header("Authorization", "Bearer " + userBToken))
                    .andExpect(status().isUnauthorized());

            // The row is still in DB but flagged as deleted.
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM users WHERE email = ? AND deleted_at IS NOT NULL",
                    Integer.class, "userb@test.com");
            org.junit.jupiter.api.Assertions.assertEquals(1, count);
        }

        @Test
        @DisplayName("401 — without token")
        void should_return401_when_noToken() throws Exception {
            mockMvc.perform(delete("/users/me"))
                    .andExpect(status().isUnauthorized());
        }
    }

    // -------------------------------------------------------------------------
    // GET /users
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("GET /users")
    class ListAllUsers {

        @Test
        @DisplayName("200 — admin gets all active users")
        void should_return200_when_admin() throws Exception {
            // setUp seeds: admin + userB.
            mockMvc.perform(get("/users")
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2));
        }

        @Test
        @DisplayName("200 — admin's listing excludes soft-deleted users")
        void should_excludeSoftDeleted() throws Exception {
            jdbcTemplate.update("UPDATE users SET deleted_at = NOW() WHERE email = ?", "userb@test.com");

            mockMvc.perform(get("/users")
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].email").value(ADMIN_EMAIL));
        }

        @Test
        @DisplayName("403 — non-admin user cannot list")
        void should_return403_when_notAdmin() throws Exception {
            mockMvc.perform(get("/users")
                            .header("Authorization", "Bearer " + userBToken))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("401 — without token")
        void should_return401_when_noToken() throws Exception {
            mockMvc.perform(get("/users"))
                    .andExpect(status().isUnauthorized());
        }
    }

    // -------------------------------------------------------------------------
    // Regression: deleted admin endpoints
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Deleted admin endpoints")
    class DeletedAdminEndpoints {

        @Test
        @DisplayName("404 — POST /admins no longer exists")
        void should_return404_when_postAdmins() throws Exception {
            mockMvc.perform(post("/admins")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("404 — DELETE /admins/users/{id} no longer exists")
        void should_return404_when_deleteUsersById() throws Exception {
            mockMvc.perform(delete("/admins/users/1")
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isNotFound());
        }
    }
}
