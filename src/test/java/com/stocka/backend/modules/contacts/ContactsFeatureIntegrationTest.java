package com.stocka.backend.modules.contacts;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.WebApplicationContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocka.backend.modules.organizations.IntegrationTestSupport;

/**
 * End-to-end integration coverage for the contact directory: CRUD with the org-role matrix,
 * deletion blocked while the contact owns pieces, and linking a contact to a member with the
 * optional piece-ownership migration.
 *
 * <p>Fixture roles mirror {@code PiecesFeatureIntegrationTest}: {@code ownerToken} (global ADMIN /
 * org OWNER), {@code managerToken}, {@code userToken}, {@code spectatorToken} and
 * {@code outsiderToken}.
 */
@SpringBootTest
@DisplayName("Contacts feature (integration)")
class ContactsFeatureIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final ObjectMapper om = new ObjectMapper();

    private MockMvc mockMvc;
    private String ownerToken;
    private String managerToken;
    private String userToken;
    private String spectatorToken;
    private String outsiderToken;
    private Integer orgId;
    private String orgSlug;
    private Integer userUserId;
    private Integer spectatorUserId;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = buildMockMvc(context);
        IntegrationTestSupport.cleanDatabase(jdbcTemplate);

        ownerToken = login(mockMvc, om, ADMIN_EMAIL, ADMIN_PASSWORD);
        orgId = createOrgAsOwner();

        managerToken = signupAndLogin(mockMvc, om, jdbcTemplate, "manager@test.com", "manager");
        userToken = signupAndLogin(mockMvc, om, jdbcTemplate, "user@test.com", "userp");
        spectatorToken = signupAndLogin(mockMvc, om, jdbcTemplate, "spect@test.com", "spect");
        outsiderToken = signupAndLogin(mockMvc, om, jdbcTemplate, "out@test.com", "outsider");

        Integer managerUserId = userIdOf("manager@test.com");
        userUserId = userIdOf("user@test.com");
        spectatorUserId = userIdOf("spect@test.com");

        addMember(orgId, managerUserId, "MANAGER");
        addMember(orgId, userUserId, "USER");
        addMember(orgId, spectatorUserId, "SPECTATOR");
    }

    // ---------- helpers ----------

    private Integer createOrgAsOwner() throws Exception {
        MvcResult r = mockMvc.perform(post("/organizations")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("name", "Acme", "slug", "acme"))))
                .andExpect(status().isOk())
                .andReturn();
        Map<?, ?> body = om.readValue(r.getResponse().getContentAsString(), Map.class);
        orgSlug = (String) body.get("slug");
        return (Integer) body.get("id");
    }

    private Integer userIdOf(String email) {
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", Integer.class, email);
    }

    /**
     * Bypasses the invitation flow and seeds a membership directly through SQL. The id is offset
     * far above the current max because Hibernate allocates member ids from a sequence that these
     * bare-SQL inserts cannot advance — some tests here create a second organization afterwards
     * (which inserts its OWNER membership through Hibernate), and a {@code MAX(id)+1} id would
     * collide with the sequence's next value.
     */
    private void addMember(Integer orgId, Integer userId, String role) {
        Long nextId = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(id), 0) + 1000 FROM organization_members", Long.class);
        jdbcTemplate.update(
                "INSERT INTO organization_members (id, user_id, organization_id, role, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                nextId, userId, orgId, role
        );
    }

    private Integer createContactAs(String token, String name, String email) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        if (email != null) body.put("email", email);
        MvcResult r = mockMvc.perform(post("/organizations/" + orgSlug + "/contacts")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        return (Integer) om.readValue(r.getResponse().getContentAsString(), Map.class).get("id");
    }

    private Integer createPieceOwnedByContact(String token, String name, Integer contactId) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        if (contactId != null) body.put("ownerContactId", contactId);
        MvcResult r = mockMvc.perform(post("/organizations/" + orgSlug + "/pieces")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        return (Integer) om.readValue(r.getResponse().getContentAsString(), Map.class).get("id");
    }

    // ---------- CRUD ----------

    @Nested
    @DisplayName("CRUD")
    class Crud {
        @Test
        @DisplayName("USER member can create; any member (incl. SPECTATOR) can list; managers edit and delete")
        void should_supportFullCrudCycle() throws Exception {
            Integer contactId = createContactAs(userToken, "Jane", "jane@ext.com");

            mockMvc.perform(get("/organizations/" + orgSlug + "/contacts")
                            .header("Authorization", "Bearer " + spectatorToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].id").value(contactId))
                    .andExpect(jsonPath("$[0].name").value("Jane"))
                    .andExpect(jsonPath("$[0].email").value("jane@ext.com"))
                    .andExpect(jsonPath("$[0].linkedUserId").doesNotExist());

            mockMvc.perform(patch("/organizations/" + orgSlug + "/contacts/" + contactId)
                            .header("Authorization", "Bearer " + managerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("lastName", "Doe", "phone", "600111222"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.lastName").value("Doe"))
                    .andExpect(jsonPath("$.phone").value("600111222"));

            mockMvc.perform(delete("/organizations/" + orgSlug + "/contacts/" + contactId)
                            .header("Authorization", "Bearer " + managerToken))
                    .andExpect(status().isNoContent());

            mockMvc.perform(get("/organizations/" + orgSlug + "/contacts")
                            .header("Authorization", "Bearer " + userToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isEmpty());
        }

        @Test
        @DisplayName("filters by q against name, last name and email")
        void should_filterByQuery() throws Exception {
            createContactAs(userToken, "Jane", "jane@ext.com");
            createContactAs(userToken, "Robert", "bob@other.com");

            mockMvc.perform(get("/organizations/" + orgSlug + "/contacts").param("q", "jane")
                            .header("Authorization", "Bearer " + userToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].name").value("Jane"));
        }

        @Test
        @DisplayName("rejects a duplicated email in the organization with 409 contacts.email_conflict")
        void should_rejectDuplicateEmail() throws Exception {
            createContactAs(userToken, "Jane", "jane@ext.com");

            mockMvc.perform(post("/organizations/" + orgSlug + "/contacts")
                            .header("Authorization", "Bearer " + userToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("name", "Other", "email", "JANE@EXT.COM"))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("contacts.email_conflict"));
        }

        @Test
        @DisplayName("rejects a blank name with 400 contacts.name_required")
        void should_rejectBlankName() throws Exception {
            mockMvc.perform(post("/organizations/" + orgSlug + "/contacts")
                            .header("Authorization", "Bearer " + userToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("name", "  "))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("contacts.name_required"));
        }
    }

    // ---------- role matrix ----------

    @Nested
    @DisplayName("Authorization matrix")
    class AuthorizationMatrix {
        @Test
        @DisplayName("SPECTATOR cannot create, edit, delete or link")
        void should_forbidSpectatorWrites() throws Exception {
            Integer contactId = createContactAs(userToken, "Jane", null);

            mockMvc.perform(post("/organizations/" + orgSlug + "/contacts")
                            .header("Authorization", "Bearer " + spectatorToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("name", "X"))))
                    .andExpect(status().isForbidden());
            mockMvc.perform(patch("/organizations/" + orgSlug + "/contacts/" + contactId)
                            .header("Authorization", "Bearer " + spectatorToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("name", "Y"))))
                    .andExpect(status().isForbidden());
            mockMvc.perform(delete("/organizations/" + orgSlug + "/contacts/" + contactId)
                            .header("Authorization", "Bearer " + spectatorToken))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("USER can create but cannot edit, delete or link")
        void should_limitUserToCreation() throws Exception {
            Integer contactId = createContactAs(userToken, "Jane", null);

            mockMvc.perform(patch("/organizations/" + orgSlug + "/contacts/" + contactId)
                            .header("Authorization", "Bearer " + userToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("name", "Y"))))
                    .andExpect(status().isForbidden());
            mockMvc.perform(delete("/organizations/" + orgSlug + "/contacts/" + contactId)
                            .header("Authorization", "Bearer " + userToken))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/organizations/" + orgSlug + "/contacts/" + contactId + "/link")
                            .header("Authorization", "Bearer " + userToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("userId", userUserId))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("an outsider cannot even list the directory")
        void should_forbidOutsider() throws Exception {
            mockMvc.perform(get("/organizations/" + orgSlug + "/contacts")
                            .header("Authorization", "Bearer " + outsiderToken))
                    .andExpect(status().isForbidden());
        }
    }

    // ---------- deletion vs ownership ----------

    @Nested
    @DisplayName("Deletion while owning pieces")
    class DeletionBlocked {
        @Test
        @DisplayName("is blocked with 409 contacts.owns_pieces until the owner is cleared")
        void should_blockThenAllowAfterClearingOwner() throws Exception {
            Integer contactId = createContactAs(userToken, "Jane", "jane@ext.com");
            Integer pieceId = createPieceOwnedByContact(userToken, "Hammer", contactId);

            mockMvc.perform(delete("/organizations/" + orgSlug + "/contacts/" + contactId)
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("contacts.owns_pieces"));

            mockMvc.perform(patch("/organizations/" + orgSlug + "/pieces/" + pieceId)
                            .header("Authorization", "Bearer " + userToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("clearOwner", true))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ownerContactId").doesNotExist());

            mockMvc.perform(delete("/organizations/" + orgSlug + "/contacts/" + contactId)
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isNoContent());
        }
    }

    // ---------- linking ----------

    @Nested
    @DisplayName("Linking a contact to a member")
    class Linking {
        @Test
        @DisplayName("links and migrates owned pieces, recording OWNER_CHANGED per piece")
        void should_linkAndMigratePieces() throws Exception {
            Integer contactId = createContactAs(userToken, "Jane", "jane@ext.com");
            Integer pieceId = createPieceOwnedByContact(userToken, "Hammer", contactId);

            mockMvc.perform(post("/organizations/" + orgSlug + "/contacts/" + contactId + "/link")
                            .header("Authorization", "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("userId", userUserId, "migratePieces", true))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.contact.linkedUserId").value(userUserId))
                    .andExpect(jsonPath("$.migratedPieces").value(1));

            mockMvc.perform(get("/organizations/" + orgSlug + "/pieces/" + pieceId)
                            .header("Authorization", "Bearer " + userToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ownerUserId").value(userUserId))
                    .andExpect(jsonPath("$.ownerContactId").doesNotExist())
                    .andExpect(jsonPath("$.owner.kind").value("USER"));

            MvcResult history = mockMvc.perform(
                            get("/organizations/" + orgSlug + "/pieces/" + pieceId + "/history")
                                    .header("Authorization", "Bearer " + userToken))
                    .andExpect(status().isOk())
                    .andReturn();
            String body = history.getResponse().getContentAsString();
            org.assertj.core.api.Assertions.assertThat(body).contains("OWNER_CHANGED");
            org.assertj.core.api.Assertions.assertThat(body).contains("Jane");
        }

        @Test
        @DisplayName("links without migrating when migratePieces is omitted")
        void should_linkWithoutMigration() throws Exception {
            Integer contactId = createContactAs(userToken, "Jane", null);
            Integer pieceId = createPieceOwnedByContact(userToken, "Hammer", contactId);

            mockMvc.perform(post("/organizations/" + orgSlug + "/contacts/" + contactId + "/link")
                            .header("Authorization", "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("userId", userUserId))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.migratedPieces").value(0));

            mockMvc.perform(get("/organizations/" + orgSlug + "/pieces/" + pieceId)
                            .header("Authorization", "Bearer " + userToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ownerContactId").value(contactId));
        }

        @Test
        @DisplayName("rejects linking to a non-member with 400 contacts.link_user_not_member")
        void should_rejectNonMember() throws Exception {
            Integer contactId = createContactAs(userToken, "Jane", null);
            Integer outsiderId = userIdOf("out@test.com");

            mockMvc.perform(post("/organizations/" + orgSlug + "/contacts/" + contactId + "/link")
                            .header("Authorization", "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("userId", outsiderId))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("contacts.link_user_not_member"));
        }

        @Test
        @DisplayName("rejects a second link with 409 contacts.already_linked")
        void should_rejectDoubleLink() throws Exception {
            Integer contactId = createContactAs(userToken, "Jane", null);
            link(contactId, userUserId, false).andExpect(status().isOk());

            link(contactId, userUserId, false)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("contacts.already_linked"));
        }

        @Test
        @DisplayName("rejects linking a second contact to the same user with 409 contacts.user_already_linked")
        void should_rejectUserAlreadyLinked() throws Exception {
            Integer first = createContactAs(userToken, "Jane", null);
            Integer second = createContactAs(userToken, "Janet", null);
            link(first, userUserId, false).andExpect(status().isOk());

            link(second, userUserId, false)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("contacts.user_already_linked"));
        }

        @Test
        @DisplayName("rejects migrating pieces to a SPECTATOR with 400 contacts.link_spectator_cannot_own")
        void should_rejectSpectatorMigration() throws Exception {
            Integer contactId = createContactAs(userToken, "Jane", null);

            link(contactId, spectatorUserId, true)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("contacts.link_spectator_cannot_own"));
        }

        @Test
        @DisplayName("unlink clears the linked user and allows re-linking")
        void should_unlink() throws Exception {
            Integer contactId = createContactAs(userToken, "Jane", null);
            link(contactId, userUserId, false).andExpect(status().isOk());

            mockMvc.perform(delete("/organizations/" + orgSlug + "/contacts/" + contactId + "/link")
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.linkedUserId").doesNotExist());

            link(contactId, userUserId, false).andExpect(status().isOk());
        }

        private org.springframework.test.web.servlet.ResultActions link(
                Integer contactId, Integer userId, boolean migrate) throws Exception {
            Map<String, Object> body = new HashMap<>();
            body.put("userId", userId);
            if (migrate) body.put("migratePieces", true);
            return mockMvc.perform(post("/organizations/" + orgSlug + "/contacts/" + contactId + "/link")
                    .header("Authorization", "Bearer " + ownerToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(om.writeValueAsString(body)));
        }
    }

    // ---------- piece owner integration ----------

    @Nested
    @DisplayName("Piece ownership by contact")
    class PieceOwnership {
        @Test
        @DisplayName("creates a piece owned by a contact and exposes the owner summary")
        void should_createPieceWithContactOwner() throws Exception {
            Integer contactId = createContactAs(userToken, "Jane", "jane@ext.com");
            Integer pieceId = createPieceOwnedByContact(userToken, "Hammer", contactId);

            mockMvc.perform(get("/organizations/" + orgSlug + "/pieces/" + pieceId)
                            .header("Authorization", "Bearer " + spectatorToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ownerContactId").value(contactId))
                    .andExpect(jsonPath("$.ownerUserId").doesNotExist())
                    .andExpect(jsonPath("$.owner.kind").value("CONTACT"))
                    .andExpect(jsonPath("$.owner.displayName").value("Jane"))
                    .andExpect(jsonPath("$.owner.email").value("jane@ext.com"));
        }

        @Test
        @DisplayName("rejects sending both ownerUserId and ownerContactId with 400 pieces.owner_conflict")
        void should_rejectBothOwnerKinds() throws Exception {
            Integer contactId = createContactAs(userToken, "Jane", null);

            mockMvc.perform(post("/organizations/" + orgSlug + "/pieces")
                            .header("Authorization", "Bearer " + userToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of(
                                    "name", "Hammer",
                                    "ownerUserId", userUserId,
                                    "ownerContactId", contactId))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("pieces.owner_conflict"));
        }

        @Test
        @DisplayName("rejects a contact of another organization with 400 contacts.not_found")
        void should_rejectForeignContact() throws Exception {
            MvcResult r = mockMvc.perform(post("/organizations")
                            .header("Authorization", "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("name", "Other", "slug", "other"))))
                    .andExpect(status().isOk())
                    .andReturn();
            String otherSlug = (String) om.readValue(r.getResponse().getContentAsString(), Map.class).get("slug");
            MvcResult c = mockMvc.perform(post("/organizations/" + otherSlug + "/contacts")
                            .header("Authorization", "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("name", "Foreign"))))
                    .andExpect(status().isCreated())
                    .andReturn();
            Integer foreignContactId =
                    (Integer) om.readValue(c.getResponse().getContentAsString(), Map.class).get("id");

            mockMvc.perform(post("/organizations/" + orgSlug + "/pieces")
                            .header("Authorization", "Bearer " + userToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of(
                                    "name", "Hammer", "ownerContactId", foreignContactId))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("contacts.not_found"));
        }

        @Test
        @DisplayName("switches owner user→contact→cleared through PATCH, and filters the list by contact")
        void should_switchOwnerKindsAndFilter() throws Exception {
            Integer contactId = createContactAs(userToken, "Jane", null);
            Integer pieceId = createPieceOwnedByContact(userToken, "Hammer", contactId);
            createPieceOwnedByContact(userToken, "Drill", null);

            mockMvc.perform(get("/organizations/" + orgSlug + "/pieces")
                            .param("ownerContactId", String.valueOf(contactId))
                            .header("Authorization", "Bearer " + userToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.content[0].id").value(pieceId));

            mockMvc.perform(patch("/organizations/" + orgSlug + "/pieces/" + pieceId)
                            .header("Authorization", "Bearer " + userToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("ownerUserId", userUserId))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ownerUserId").value(userUserId))
                    .andExpect(jsonPath("$.ownerContactId").doesNotExist());

            mockMvc.perform(patch("/organizations/" + orgSlug + "/pieces/" + pieceId)
                            .header("Authorization", "Bearer " + userToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("ownerContactId", contactId))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ownerContactId").value(contactId))
                    .andExpect(jsonPath("$.ownerUserId").doesNotExist());

            mockMvc.perform(patch("/organizations/" + orgSlug + "/pieces/" + pieceId)
                            .header("Authorization", "Bearer " + userToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("clearOwner", true))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ownerContactId").doesNotExist())
                    .andExpect(jsonPath("$.ownerUserId").doesNotExist());
        }
    }
}
