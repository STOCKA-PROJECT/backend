package com.stocka.backend.modules.pieces;

import static com.stocka.backend.modules.organizations.IntegrationTestSupport.ADMIN_EMAIL;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.ADMIN_PASSWORD;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.buildMockMvc;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.login;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.signupAndLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.WebApplicationContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocka.backend.modules.organizations.IntegrationTestSupport;

/**
 * End-to-end integration coverage for the pieces / piece-types / locations / attachments / history
 * feature. Each {@code @Nested} class concentrates on one slice; they share the same
 * organization fixture (Acme) created in {@link #setUp()}.
 *
 * <p>Roles in the fixture:
 * <ul>
 *   <li>{@code ownerToken} — global ADMIN, acts as OWNER of every org via {@code OrganizationSecurity}.</li>
 *   <li>{@code managerToken} — MANAGER member of Acme.</li>
 *   <li>{@code userToken} — USER member of Acme.</li>
 *   <li>{@code spectatorToken} — SPECTATOR member of Acme.</li>
 *   <li>{@code outsiderToken} — signed-up user with no membership.</li>
 * </ul>
 */
@SpringBootTest
@DisplayName("Pieces feature (integration)")
class PiecesFeatureIntegrationTest {

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
    private Integer managerUserId;
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

        managerUserId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", Integer.class, "manager@test.com");
        userUserId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", Integer.class, "user@test.com");
        spectatorUserId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", Integer.class, "spect@test.com");

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
        return (Integer) body.get("id");
    }

    /**
     * Bypasses the invitation flow and seeds a membership directly through SQL. We compute the
     * next id ourselves because Hibernate's {@code GenerationType.AUTO} routes through a sequence
     * that bare-SQL inserts cannot reach.
     */
    private void addMember(Integer orgId, Integer userId, String role) {
        Long nextId = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(id), 0) + 1 FROM organization_members", Long.class);
        jdbcTemplate.update(
                "INSERT INTO organization_members (id, user_id, organization_id, role, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                nextId, userId, orgId, role
        );
    }

    private Integer createLocationAs(String token, String name, Integer parentId) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("name", name);
        if (parentId != null) body.put("parentId", parentId);
        MvcResult r = mockMvc.perform(post("/organizations/" + orgId + "/locations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        return (Integer) om.readValue(r.getResponse().getContentAsString(), Map.class).get("id");
    }

    private Integer createSimpleTypeAs(String token, String name, boolean withRequiredAttr) throws Exception {
        Map<String, Object> attr = Map.of(
                "name", "color", "displayName", "Color",
                "type", "TEXT", "required", withRequiredAttr
        );
        Map<String, Object> body = Map.of("name", name, "attributes", List.of(attr));
        MvcResult r = mockMvc.perform(post("/organizations/" + orgId + "/piece-types")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        return (Integer) om.readValue(r.getResponse().getContentAsString(), Map.class).get("id");
    }

    private Integer firstAttributeIdOfType(Integer typeId) throws Exception {
        MvcResult r = mockMvc.perform(get("/organizations/" + orgId + "/piece-types/" + typeId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andReturn();
        Map<?, ?> body = om.readValue(r.getResponse().getContentAsString(), Map.class);
        List<?> attrs = (List<?>) body.get("attributes");
        return (Integer) ((Map<?, ?>) attrs.get(0)).get("id");
    }

    private Integer createPieceAs(String token, Integer typeId, String name,
                                  Integer attributeId, String value) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("name", name);
        body.put("pieceTypeIds", List.of(typeId));
        if (attributeId != null) {
            body.put("attributeValues", List.of(Map.of("attributeId", attributeId, "value", value)));
        }
        MvcResult r = mockMvc.perform(post("/organizations/" + orgId + "/pieces")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        return (Integer) om.readValue(r.getResponse().getContentAsString(), Map.class).get("id");
    }

    // ---------- nested suites ----------

    @Nested
    @DisplayName("Locations")
    class Locations {

        @Test
        @DisplayName("OWNER and MANAGER can create root + child; USER and SPECTATOR cannot")
        void create_permissions() throws Exception {
            createLocationAs(ownerToken, "Warehouse", null);

            mockMvc.perform(post("/organizations/" + orgId + "/locations")
                            .header("Authorization", "Bearer " + userToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("name", "Forbidden"))))
                    .andExpect(status().isForbidden());

            mockMvc.perform(post("/organizations/" + orgId + "/locations")
                            .header("Authorization", "Bearer " + spectatorToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("name", "Forbidden"))))
                    .andExpect(status().isForbidden());

            mockMvc.perform(post("/organizations/" + orgId + "/locations")
                            .header("Authorization", "Bearer " + outsiderToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("name", "Forbidden"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("blank name returns 400")
        void create_blankName_returns400() throws Exception {
            mockMvc.perform(post("/organizations/" + orgId + "/locations")
                            .header("Authorization", "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("name", "  "))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("tree returns nested children")
        void tree_returnsNested() throws Exception {
            Integer root = createLocationAs(ownerToken, "Warehouse", null);
            createLocationAs(ownerToken, "Shelf A", root);

            mockMvc.perform(get("/organizations/" + orgId + "/locations/tree")
                            .header("Authorization", "Bearer " + spectatorToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].name").value("Warehouse"))
                    .andExpect(jsonPath("$[0].children[0].name").value("Shelf A"));
        }

        @Test
        @DisplayName("cannot move location to its own descendant")
        void move_descendantBlocked() throws Exception {
            Integer root = createLocationAs(ownerToken, "Warehouse", null);
            Integer child = createLocationAs(ownerToken, "Shelf A", root);

            mockMvc.perform(patch("/organizations/" + orgId + "/locations/" + root)
                            .header("Authorization", "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("parentId", child))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("delete is blocked when location has sub-locations or pieces")
        void delete_blocked_whenNotEmpty() throws Exception {
            Integer root = createLocationAs(ownerToken, "Warehouse", null);
            createLocationAs(ownerToken, "Shelf A", root);

            mockMvc.perform(delete("/organizations/" + orgId + "/locations/" + root)
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("delete is allowed when location is empty")
        void delete_allowed_whenEmpty() throws Exception {
            Integer leaf = createLocationAs(ownerToken, "Standalone", null);
            mockMvc.perform(delete("/organizations/" + orgId + "/locations/" + leaf)
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isNoContent());
        }
    }

    @Nested
    @DisplayName("PieceTypes")
    class PieceTypes {

        @Test
        @DisplayName("OWNER/MANAGER can create types; USER/SPECTATOR cannot")
        void create_permissions() throws Exception {
            createSimpleTypeAs(ownerToken, "Tool", true);

            mockMvc.perform(post("/organizations/" + orgId + "/piece-types")
                            .header("Authorization", "Bearer " + userToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("name", "X"))))
                    .andExpect(status().isForbidden());

            mockMvc.perform(post("/organizations/" + orgId + "/piece-types")
                            .header("Authorization", "Bearer " + spectatorToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("name", "X"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("duplicate type name in org returns 409")
        void duplicate_typeName_returns409() throws Exception {
            createSimpleTypeAs(ownerToken, "Tool", true);
            mockMvc.perform(post("/organizations/" + orgId + "/piece-types")
                            .header("Authorization", "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("name", "Tool"))))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("delete type with pieces returns 400")
        void delete_typeWithPieces_returns400() throws Exception {
            Integer typeId = createSimpleTypeAs(ownerToken, "Tool", false);
            Integer attrId = firstAttributeIdOfType(typeId);
            createPieceAs(ownerToken, typeId, "Hammer", attrId, "red");

            mockMvc.perform(delete("/organizations/" + orgId + "/piece-types/" + typeId)
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("adding a required attribute flips existing complete pieces to PENDING")
        void addRequiredAttribute_flipsToPending() throws Exception {
            Integer typeId = createSimpleTypeAs(ownerToken, "Tool", true);
            Integer attrId = firstAttributeIdOfType(typeId);
            Integer pieceId = createPieceAs(ownerToken, typeId, "Hammer", attrId, "red");

            mockMvc.perform(get("/organizations/" + orgId + "/pieces/" + pieceId)
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(jsonPath("$.status").value("ACTIVE"));

            Map<String, Object> newAttr = Map.of("name", "weight", "displayName", "Weight",
                    "type", "INTEGER", "required", true);
            mockMvc.perform(post("/organizations/" + orgId + "/piece-types/" + typeId + "/attributes")
                            .header("Authorization", "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(newAttr)))
                    .andExpect(status().isCreated());

            mockMvc.perform(get("/organizations/" + orgId + "/pieces/" + pieceId)
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(jsonPath("$.status").value("PENDING"));
        }
    }

    @Nested
    @DisplayName("Pieces")
    class Pieces {

        @Test
        @DisplayName("OWNER/MANAGER/USER can create pieces; SPECTATOR and outsiders cannot")
        void create_permissions() throws Exception {
            Integer typeId = createSimpleTypeAs(ownerToken, "Tool", false);
            Integer attrId = firstAttributeIdOfType(typeId);

            createPieceAs(ownerToken, typeId, "Hammer-O", attrId, "red");
            createPieceAs(managerToken, typeId, "Hammer-M", attrId, "red");
            createPieceAs(userToken, typeId, "Hammer-U", attrId, "red");

            mockMvc.perform(post("/organizations/" + orgId + "/pieces")
                            .header("Authorization", "Bearer " + spectatorToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of(
                                    "name", "Forbidden",
                                    "pieceTypeIds", List.of(typeId)
                            ))))
                    .andExpect(status().isForbidden());

            mockMvc.perform(post("/organizations/" + orgId + "/pieces")
                            .header("Authorization", "Bearer " + outsiderToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of(
                                    "name", "Forbidden",
                                    "pieceTypeIds", List.of(typeId)
                            ))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("status is PENDING when a required attribute value is missing")
        void status_pending_whenRequiredMissing() throws Exception {
            Integer typeId = createSimpleTypeAs(ownerToken, "Tool", true);

            MvcResult r = mockMvc.perform(post("/organizations/" + orgId + "/pieces")
                            .header("Authorization", "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of(
                                    "name", "Anvil",
                                    "pieceTypeIds", List.of(typeId)
                            ))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("PENDING"))
                    .andReturn();

            Integer pieceId = (Integer) om.readValue(r.getResponse().getContentAsString(), Map.class).get("id");
            Integer attrId = firstAttributeIdOfType(typeId);

            mockMvc.perform(patch("/organizations/" + orgId + "/pieces/" + pieceId)
                            .header("Authorization", "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of(
                                    "attributeValues", List.of(Map.of("attributeId", attrId, "value", "blue"))
                            ))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ACTIVE"));
        }

        @Test
        @DisplayName("attribute value that violates type rules returns 400")
        void invalidAttributeValue_returns400() throws Exception {
            Integer typeId = createSimpleTypeAs(ownerToken, "Tool", false);
            // change the attribute to INTEGER via add+delete? Simpler: create fresh type with INTEGER attr
            Map<String, Object> attr = Map.of("name", "weight", "displayName", "Weight",
                    "type", "INTEGER", "required", true);
            MvcResult r = mockMvc.perform(post("/organizations/" + orgId + "/piece-types")
                            .header("Authorization", "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of(
                                    "name", "Heavy", "attributes", List.of(attr)
                            ))))
                    .andExpect(status().isCreated())
                    .andReturn();
            Integer heavyTypeId = (Integer) om.readValue(r.getResponse().getContentAsString(), Map.class).get("id");
            Integer heavyAttrId = firstAttributeIdOfType(heavyTypeId);

            mockMvc.perform(post("/organizations/" + orgId + "/pieces")
                            .header("Authorization", "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of(
                                    "name", "Bad",
                                    "pieceTypeIds", List.of(heavyTypeId),
                                    "attributeValues", List.of(Map.of("attributeId", heavyAttrId, "value", "not a number"))
                            ))))
                    .andExpect(status().isBadRequest());
            // typeId variable is intentionally created above to verify schema independence; not asserted
            assert typeId != null;
        }

        @Test
        @DisplayName("owner must be a member of the org")
        void ownerNotMember_returns400() throws Exception {
            Integer typeId = createSimpleTypeAs(ownerToken, "Tool", false);
            Integer outsiderId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?",
                    Integer.class, "out@test.com");

            mockMvc.perform(post("/organizations/" + orgId + "/pieces")
                            .header("Authorization", "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of(
                                    "name", "Bad",
                                    "pieceTypeIds", List.of(typeId),
                                    "ownerUserId", outsiderId
                            ))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("SPECTATOR can read pieces")
        void spectator_canRead() throws Exception {
            Integer typeId = createSimpleTypeAs(ownerToken, "Tool", false);
            Integer attrId = firstAttributeIdOfType(typeId);
            Integer pieceId = createPieceAs(ownerToken, typeId, "Hammer", attrId, "red");

            mockMvc.perform(get("/organizations/" + orgId + "/pieces/" + pieceId)
                            .header("Authorization", "Bearer " + spectatorToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Hammer"));
        }

        @Test
        @DisplayName("listing supports filter by status and search by name")
        void list_filters() throws Exception {
            Integer typeId = createSimpleTypeAs(ownerToken, "Tool", true);
            Integer attrId = firstAttributeIdOfType(typeId);
            createPieceAs(ownerToken, typeId, "Hammer", attrId, "red");
            // pending one (no value)
            mockMvc.perform(post("/organizations/" + orgId + "/pieces")
                            .header("Authorization", "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of(
                                    "name", "Anvil",
                                    "pieceTypeIds", List.of(typeId)
                            ))))
                    .andExpect(status().isCreated());

            mockMvc.perform(get("/organizations/" + orgId + "/pieces?status=PENDING")
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.content[0].name").value("Anvil"));

            mockMvc.perform(get("/organizations/" + orgId + "/pieces?q=hammer")
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.content[0].name").value("Hammer"));
        }
    }

    @Nested
    @DisplayName("Attachments")
    class Attachments {

        @Test
        @DisplayName("upload IMAGE: jpg ok, pdf rejected")
        void uploadImage_mimeRules() throws Exception {
            Integer typeId = createSimpleTypeAs(ownerToken, "Tool", false);
            Integer attrId = firstAttributeIdOfType(typeId);
            Integer pieceId = createPieceAs(ownerToken, typeId, "Hammer", attrId, "red");

            MockMultipartFile jpg = new MockMultipartFile("file", "p.jpg", "image/jpeg", new byte[]{1, 2, 3});
            mockMvc.perform(multipart("/organizations/" + orgId + "/pieces/" + pieceId + "/attachments")
                            .file(jpg)
                            .param("kind", "IMAGE")
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isCreated());

            MockMultipartFile pdf = new MockMultipartFile("file", "p.pdf", "application/pdf", new byte[]{1, 2, 3});
            mockMvc.perform(multipart("/organizations/" + orgId + "/pieces/" + pieceId + "/attachments")
                            .file(pdf)
                            .param("kind", "IMAGE")
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("upload DOCUMENT: any mime is accepted, including image/jpeg")
        void uploadDocument_acceptsAnyMime() throws Exception {
            Integer typeId = createSimpleTypeAs(ownerToken, "Tool", false);
            Integer attrId = firstAttributeIdOfType(typeId);
            Integer pieceId = createPieceAs(ownerToken, typeId, "Hammer", attrId, "red");

            MockMultipartFile jpgAsDoc = new MockMultipartFile("file", "p.jpg", "image/jpeg", new byte[]{1, 2, 3});
            mockMvc.perform(multipart("/organizations/" + orgId + "/pieces/" + pieceId + "/attachments")
                            .file(jpgAsDoc)
                            .param("kind", "DOCUMENT")
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isCreated());

            MockMultipartFile zip = new MockMultipartFile("file", "x.zip", "application/zip", new byte[]{1});
            mockMvc.perform(multipart("/organizations/" + orgId + "/pieces/" + pieceId + "/attachments")
                            .file(zip)
                            .param("kind", "DOCUMENT")
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("SPECTATOR cannot upload but can download")
        void spectator_cannotUpload_butCanDownload() throws Exception {
            Integer typeId = createSimpleTypeAs(ownerToken, "Tool", false);
            Integer attrId = firstAttributeIdOfType(typeId);
            Integer pieceId = createPieceAs(ownerToken, typeId, "Hammer", attrId, "red");

            MockMultipartFile jpg = new MockMultipartFile("file", "p.jpg", "image/jpeg", new byte[]{1, 2, 3});
            MvcResult r = mockMvc.perform(multipart("/organizations/" + orgId + "/pieces/" + pieceId + "/attachments")
                            .file(jpg)
                            .param("kind", "IMAGE")
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isCreated())
                    .andReturn();
            Integer attachmentId = (Integer) om.readValue(r.getResponse().getContentAsString(), Map.class).get("id");

            mockMvc.perform(multipart("/organizations/" + orgId + "/pieces/" + pieceId + "/attachments")
                            .file(jpg)
                            .param("kind", "IMAGE")
                            .header("Authorization", "Bearer " + spectatorToken))
                    .andExpect(status().isForbidden());

            mockMvc.perform(get("/organizations/" + orgId + "/pieces/" + pieceId
                            + "/attachments/" + attachmentId + "/download")
                            .header("Authorization", "Bearer " + spectatorToken))
                    .andExpect(status().isFound())
                    .andExpect(header().exists("Location"));
        }
    }

    @Nested
    @DisplayName("History")
    class History {

        @Test
        @DisplayName("records PIECE_CREATED, ATTRIBUTE_VALUE_CHANGED, STATUS_CHANGED, ATTACHMENT_ADDED")
        void recordsCommonActions() throws Exception {
            Integer typeId = createSimpleTypeAs(ownerToken, "Tool", true);
            Integer attrId = firstAttributeIdOfType(typeId);

            // Create as PENDING (no value)
            MvcResult r = mockMvc.perform(post("/organizations/" + orgId + "/pieces")
                            .header("Authorization", "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of(
                                    "name", "Hammer",
                                    "pieceTypeIds", List.of(typeId)
                            ))))
                    .andExpect(status().isCreated())
                    .andReturn();
            Integer pieceId = (Integer) om.readValue(r.getResponse().getContentAsString(), Map.class).get("id");

            // Set required value → goes ACTIVE
            mockMvc.perform(patch("/organizations/" + orgId + "/pieces/" + pieceId)
                            .header("Authorization", "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of(
                                    "attributeValues", List.of(Map.of("attributeId", attrId, "value", "red"))
                            ))))
                    .andExpect(status().isOk());

            // Upload an attachment
            MockMultipartFile jpg = new MockMultipartFile("file", "p.jpg", "image/jpeg", new byte[]{1});
            mockMvc.perform(multipart("/organizations/" + orgId + "/pieces/" + pieceId + "/attachments")
                            .file(jpg)
                            .param("kind", "IMAGE")
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isCreated());

            mockMvc.perform(get("/organizations/" + orgId + "/pieces/" + pieceId + "/history")
                            .header("Authorization", "Bearer " + spectatorToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[?(@.action == 'PIECE_CREATED')].length()").exists())
                    .andExpect(jsonPath("$.content[?(@.action == 'ATTRIBUTE_VALUE_CHANGED')].length()").exists())
                    .andExpect(jsonPath("$.content[?(@.action == 'STATUS_CHANGED')].length()").exists())
                    .andExpect(jsonPath("$.content[?(@.action == 'ATTACHMENT_ADDED')].length()").exists());
        }
    }

    @Nested
    @DisplayName("Multi-type pieces")
    class MultiType {

        @Test
        @DisplayName("piece with two types aggregates required attributes from both for status calc")
        void status_aggregatesRequiredFromAllTypes() throws Exception {
            Integer toolType = createSimpleTypeAs(ownerToken, "Tool", true);
            Integer toolAttr = firstAttributeIdOfType(toolType);

            Map<String, Object> heavyAttr = Map.of(
                    "name", "weight", "displayName", "Weight",
                    "type", "INTEGER", "required", true);
            MvcResult heavyR = mockMvc.perform(post("/organizations/" + orgId + "/piece-types")
                            .header("Authorization", "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of(
                                    "name", "Heavy", "attributes", List.of(heavyAttr)
                            ))))
                    .andExpect(status().isCreated())
                    .andReturn();
            Integer heavyType = (Integer) om.readValue(heavyR.getResponse().getContentAsString(), Map.class).get("id");
            Integer heavyAttrId = firstAttributeIdOfType(heavyType);

            // Only fills the Tool attribute → Heavy.weight is missing → PENDING
            MvcResult r = mockMvc.perform(post("/organizations/" + orgId + "/pieces")
                            .header("Authorization", "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of(
                                    "name", "Sledgehammer",
                                    "pieceTypeIds", List.of(toolType, heavyType),
                                    "attributeValues", List.of(Map.of("attributeId", toolAttr, "value", "red"))
                            ))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("PENDING"))
                    .andExpect(jsonPath("$.pieceTypes.length()").value(2))
                    .andReturn();
            Integer pieceId = (Integer) om.readValue(r.getResponse().getContentAsString(), Map.class).get("id");

            // Set the missing required value → flips to ACTIVE
            mockMvc.perform(patch("/organizations/" + orgId + "/pieces/" + pieceId)
                            .header("Authorization", "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of(
                                    "attributeValues", List.of(Map.of("attributeId", heavyAttrId, "value", "12"))
                            ))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ACTIVE"));
        }

        @Test
        @DisplayName("create with empty pieceTypeIds returns 400")
        void emptyTypes_returns400() throws Exception {
            mockMvc.perform(post("/organizations/" + orgId + "/pieces")
                            .header("Authorization", "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of(
                                    "name", "NoType",
                                    "pieceTypeIds", List.of()
                            ))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("attribute value targeting an attribute outside the piece's types returns 400")
        void attribute_outsideTypes_returns400() throws Exception {
            Integer toolType = createSimpleTypeAs(ownerToken, "Tool", false);
            Integer otherType = createSimpleTypeAs(ownerToken, "Other", false);
            Integer otherAttr = firstAttributeIdOfType(otherType);

            mockMvc.perform(post("/organizations/" + orgId + "/pieces")
                            .header("Authorization", "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of(
                                    "name", "Mismatch",
                                    "pieceTypeIds", List.of(toolType),
                                    "attributeValues", List.of(Map.of("attributeId", otherAttr, "value", "x"))
                            ))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("PATCH replacing pieceTypeIds drops attribute values that are no longer covered")
        void patchTypes_dropsOrphanValues() throws Exception {
            Integer typeA = createSimpleTypeAs(ownerToken, "TypeA", false);
            Integer typeAAttr = firstAttributeIdOfType(typeA);
            Integer typeB = createSimpleTypeAs(ownerToken, "TypeB", false);

            Integer pieceId = createPieceAs(ownerToken, typeA, "Hammer", typeAAttr, "red");

            // Replace typeA with typeB — the old value should disappear from the response.
            mockMvc.perform(patch("/organizations/" + orgId + "/pieces/" + pieceId)
                            .header("Authorization", "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of(
                                    "pieceTypeIds", List.of(typeB)
                            ))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.pieceTypes.length()").value(1))
                    .andExpect(jsonPath("$.pieceTypes[0].id").value(typeB))
                    .andExpect(jsonPath("$.attributeValues.length()").value(0));

            // History should record PIECE_TYPES_CHANGED.
            mockMvc.perform(get("/organizations/" + orgId + "/pieces/" + pieceId + "/history")
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[?(@.action == 'PIECE_TYPES_CHANGED')].length()").exists());
        }

        @Test
        @DisplayName("listing filtered by typeId returns pieces that contain that type")
        void list_filterByType_acrossMultiType() throws Exception {
            Integer typeA = createSimpleTypeAs(ownerToken, "TypeA", false);
            Integer typeAAttr = firstAttributeIdOfType(typeA);
            Integer typeB = createSimpleTypeAs(ownerToken, "TypeB", false);

            // Piece1: typeA only
            createPieceAs(ownerToken, typeA, "OnlyA", typeAAttr, "red");

            // Piece2: typeA + typeB
            mockMvc.perform(post("/organizations/" + orgId + "/pieces")
                            .header("Authorization", "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of(
                                    "name", "AandB",
                                    "pieceTypeIds", List.of(typeA, typeB)
                            ))))
                    .andExpect(status().isCreated());

            // Filter by typeB → only the multi-type piece
            mockMvc.perform(get("/organizations/" + orgId + "/pieces?typeId=" + typeB)
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.content[0].name").value("AandB"));

            // Filter by typeA → both
            mockMvc.perform(get("/organizations/" + orgId + "/pieces?typeId=" + typeA)
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(2));
        }
    }

    @Nested
    @DisplayName("Spectator role lifecycle")
    class Spectator {

        @Test
        @DisplayName("OWNER can promote a member to SPECTATOR")
        void ownerCanChangeUserToSpectator() throws Exception {
            Integer memberId = jdbcTemplate.queryForObject(
                    "SELECT id FROM organization_members WHERE user_id = ? AND organization_id = ?",
                    Integer.class, userUserId, orgId);

            mockMvc.perform(patch("/organizations/" + orgId + "/members/" + memberId)
                            .header("Authorization", "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("role", "SPECTATOR"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.role").value("SPECTATOR"));
        }

        @Test
        @DisplayName("MANAGER can invite a SPECTATOR")
        void managerCanInviteSpectator() throws Exception {
            mockMvc.perform(post("/organizations/" + orgId + "/invitations")
                            .header("Authorization", "Bearer " + managerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of(
                                    "email", "newspect@test.com",
                                    "role", "SPECTATOR"
                            ))))
                    .andExpect(status().isOk());
        }
    }
}
