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

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

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
    private String orgSlug;
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
        orgSlug = (String) body.get("slug");
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
        MvcResult r = mockMvc.perform(post("/organizations/" + orgSlug + "/locations")
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
        MvcResult r = mockMvc.perform(post("/organizations/" + orgSlug + "/piece-types")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        return (Integer) om.readValue(r.getResponse().getContentAsString(), Map.class).get("id");
    }

    private Integer firstAttributeIdOfType(Integer typeId) throws Exception {
        MvcResult r = mockMvc.perform(get("/organizations/" + orgSlug + "/piece-types/" + typeId)
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
        MvcResult r = mockMvc.perform(post("/organizations/" + orgSlug + "/pieces")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        return (Integer) om.readValue(r.getResponse().getContentAsString(), Map.class).get("id");
    }

    /**
     * Returns a real PNG of the given dimensions. Required after issue #14: upload validation
     * now reads the image header, so synthetic byte arrays no longer pass the IMAGE pipeline.
     */
    private static byte[] pngBytes(int width, int height) {
        return imageBytes("png", width, height, BufferedImage.TYPE_INT_ARGB);
    }

    private static byte[] jpegBytes(int width, int height) {
        return imageBytes("jpeg", width, height, BufferedImage.TYPE_INT_RGB);
    }

    private static byte[] imageBytes(String format, int width, int height, int type) {
        try {
            BufferedImage img = new BufferedImage(width, height, type);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, format, baos);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---------- nested suites ----------

    @Nested
    @DisplayName("Locations")
    class Locations {

        @Test
        @DisplayName("OWNER and MANAGER can create root + child; USER and SPECTATOR cannot")
        void create_permissions() throws Exception {
            createLocationAs(ownerToken, "Warehouse", null);

            mockMvc.perform(post("/organizations/" + orgSlug + "/locations")
                            .header("Authorization", "Bearer " + userToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("name", "Forbidden"))))
                    .andExpect(status().isForbidden());

            mockMvc.perform(post("/organizations/" + orgSlug + "/locations")
                            .header("Authorization", "Bearer " + spectatorToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("name", "Forbidden"))))
                    .andExpect(status().isForbidden());

            mockMvc.perform(post("/organizations/" + orgSlug + "/locations")
                            .header("Authorization", "Bearer " + outsiderToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("name", "Forbidden"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("blank name returns 400")
        void create_blankName_returns400() throws Exception {
            mockMvc.perform(post("/organizations/" + orgSlug + "/locations")
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

            mockMvc.perform(get("/organizations/" + orgSlug + "/locations/tree")
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

            mockMvc.perform(patch("/organizations/" + orgSlug + "/locations/" + root)
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

            mockMvc.perform(delete("/organizations/" + orgSlug + "/locations/" + root)
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("delete is allowed when location is empty")
        void delete_allowed_whenEmpty() throws Exception {
            Integer leaf = createLocationAs(ownerToken, "Standalone", null);
            mockMvc.perform(delete("/organizations/" + orgSlug + "/locations/" + leaf)
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

            mockMvc.perform(post("/organizations/" + orgSlug + "/piece-types")
                            .header("Authorization", "Bearer " + userToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("name", "X"))))
                    .andExpect(status().isForbidden());

            mockMvc.perform(post("/organizations/" + orgSlug + "/piece-types")
                            .header("Authorization", "Bearer " + spectatorToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("name", "X"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("duplicate type name in org returns 409 with piece_types.name_conflict code")
        void duplicate_typeName_returns409() throws Exception {
            createSimpleTypeAs(ownerToken, "Tool", true);
            mockMvc.perform(post("/organizations/" + orgSlug + "/piece-types")
                            .header("Authorization", "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("name", "Tool"))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("piece_types.name_conflict"));
        }

        @Test
        @DisplayName("can recreate a type with the same name after soft-delete (slot is freed)")
        void recreateType_afterSoftDelete_succeeds() throws Exception {
            Integer typeId = createSimpleTypeAs(ownerToken, "Tira Led", true);
            mockMvc.perform(delete("/organizations/" + orgSlug + "/piece-types/" + typeId)
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isNoContent());

            // Should not collide with the soft-deleted row at the DB level.
            createSimpleTypeAs(ownerToken, "Tira Led", true);

            mockMvc.perform(get("/organizations/" + orgSlug + "/piece-types")
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].name").value("Tira Led"));
        }

        @Test
        @DisplayName("two soft-deleted types with the same original name coexist without UK collisions")
        void softDelete_twiceSameName_doesNotCollide() throws Exception {
            Integer first = createSimpleTypeAs(ownerToken, "Tira Led", true);
            mockMvc.perform(delete("/organizations/" + orgSlug + "/piece-types/" + first)
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isNoContent());

            Integer second = createSimpleTypeAs(ownerToken, "Tira Led", true);
            mockMvc.perform(delete("/organizations/" + orgSlug + "/piece-types/" + second)
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isNoContent());

            mockMvc.perform(get("/organizations/" + orgSlug + "/piece-types")
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));

            Long deletedRows = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM piece_types WHERE deleted_at IS NOT NULL", Long.class);
            org.assertj.core.api.Assertions.assertThat(deletedRows).isEqualTo(2L);
        }

        @Test
        @DisplayName("can recreate an attribute with the same technical name after soft-delete")
        void recreateAttribute_afterSoftDelete_succeeds() throws Exception {
            Integer typeId = createSimpleTypeAs(ownerToken, "Tool", true);
            Integer attrId = firstAttributeIdOfType(typeId);

            mockMvc.perform(delete(
                            "/organizations/" + orgSlug + "/piece-types/" + typeId + "/attributes/" + attrId)
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isNoContent());

            Map<String, Object> recreated = Map.of(
                    "name", "color", "displayName", "Color",
                    "type", "TEXT", "required", true);
            mockMvc.perform(post("/organizations/" + orgSlug + "/piece-types/" + typeId + "/attributes")
                            .header("Authorization", "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(recreated)))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("the same type name can coexist in two different organizations")
        void crossOrg_sameTypeName_isolated() throws Exception {
            createSimpleTypeAs(ownerToken, "Tira Led", true);

            // Seed the second org through SQL: the global ADMIN (ownerToken) acts as OWNER of
            // every org via OrganizationSecurity, so we don't need an explicit member row. Going
            // through POST /organizations would clash with the bare-SQL members seeded in setUp().
            Long nextOrgId = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(MAX(id), 0) + 1 FROM organizations", Long.class);
            jdbcTemplate.update(
                    "INSERT INTO organizations (id, name, slug, created_at, updated_at) "
                            + "VALUES (?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    nextOrgId, "Beta", "beta");
            String otherOrgSlug = "beta";

            Map<String, Object> attr = Map.of(
                    "name", "color", "displayName", "Color",
                    "type", "TEXT", "required", true);
            Map<String, Object> body = Map.of("name", "Tira Led", "attributes", List.of(attr));
            mockMvc.perform(post("/organizations/" + otherOrgSlug + "/piece-types")
                            .header("Authorization", "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(body)))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("delete type with pieces returns 400")
        void delete_typeWithPieces_returns400() throws Exception {
            Integer typeId = createSimpleTypeAs(ownerToken, "Tool", false);
            Integer attrId = firstAttributeIdOfType(typeId);
            createPieceAs(ownerToken, typeId, "Hammer", attrId, "red");

            mockMvc.perform(delete("/organizations/" + orgSlug + "/piece-types/" + typeId)
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("adding a required attribute flips existing complete pieces to PENDING")
        void addRequiredAttribute_flipsToPending() throws Exception {
            Integer typeId = createSimpleTypeAs(ownerToken, "Tool", true);
            Integer attrId = firstAttributeIdOfType(typeId);
            Integer pieceId = createPieceAs(ownerToken, typeId, "Hammer", attrId, "red");

            mockMvc.perform(get("/organizations/" + orgSlug + "/pieces/" + pieceId)
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(jsonPath("$.status").value("ACTIVE"));

            Map<String, Object> newAttr = Map.of("name", "weight", "displayName", "Weight",
                    "type", "INTEGER", "required", true);
            mockMvc.perform(post("/organizations/" + orgSlug + "/piece-types/" + typeId + "/attributes")
                            .header("Authorization", "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(newAttr)))
                    .andExpect(status().isCreated());

            mockMvc.perform(get("/organizations/" + orgSlug + "/pieces/" + pieceId)
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

            mockMvc.perform(post("/organizations/" + orgSlug + "/pieces")
                            .header("Authorization", "Bearer " + spectatorToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of(
                                    "name", "Forbidden",
                                    "pieceTypeIds", List.of(typeId)
                            ))))
                    .andExpect(status().isForbidden());

            mockMvc.perform(post("/organizations/" + orgSlug + "/pieces")
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

            MvcResult r = mockMvc.perform(post("/organizations/" + orgSlug + "/pieces")
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

            mockMvc.perform(patch("/organizations/" + orgSlug + "/pieces/" + pieceId)
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
            MvcResult r = mockMvc.perform(post("/organizations/" + orgSlug + "/piece-types")
                            .header("Authorization", "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of(
                                    "name", "Heavy", "attributes", List.of(attr)
                            ))))
                    .andExpect(status().isCreated())
                    .andReturn();
            Integer heavyTypeId = (Integer) om.readValue(r.getResponse().getContentAsString(), Map.class).get("id");
            Integer heavyAttrId = firstAttributeIdOfType(heavyTypeId);

            mockMvc.perform(post("/organizations/" + orgSlug + "/pieces")
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

            mockMvc.perform(post("/organizations/" + orgSlug + "/pieces")
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

            mockMvc.perform(get("/organizations/" + orgSlug + "/pieces/" + pieceId)
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
            mockMvc.perform(post("/organizations/" + orgSlug + "/pieces")
                            .header("Authorization", "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of(
                                    "name", "Anvil",
                                    "pieceTypeIds", List.of(typeId)
                            ))))
                    .andExpect(status().isCreated());

            mockMvc.perform(get("/organizations/" + orgSlug + "/pieces?status=PENDING")
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.content[0].name").value("Anvil"));

            mockMvc.perform(get("/organizations/" + orgSlug + "/pieces?q=hammer")
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

            MockMultipartFile jpg = new MockMultipartFile("file", "p.jpg", "image/jpeg", jpegBytes(1, 1));
            mockMvc.perform(multipart("/organizations/" + orgSlug + "/pieces/" + pieceId + "/attachments")
                            .file(jpg)
                            .param("kind", "IMAGE")
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isCreated());

            MockMultipartFile pdf = new MockMultipartFile("file", "p.pdf", "application/pdf", new byte[]{1, 2, 3});
            mockMvc.perform(multipart("/organizations/" + orgSlug + "/pieces/" + pieceId + "/attachments")
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

            MockMultipartFile jpgAsDoc = new MockMultipartFile("file", "p.jpg", "image/jpeg", jpegBytes(1, 1));
            mockMvc.perform(multipart("/organizations/" + orgSlug + "/pieces/" + pieceId + "/attachments")
                            .file(jpgAsDoc)
                            .param("kind", "DOCUMENT")
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isCreated());

            MockMultipartFile zip = new MockMultipartFile("file", "x.zip", "application/zip", new byte[]{1});
            mockMvc.perform(multipart("/organizations/" + orgSlug + "/pieces/" + pieceId + "/attachments")
                            .file(zip)
                            .param("kind", "DOCUMENT")
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("falsified Content-Type: image/png with non-image bytes is rejected (400)")
        void uploadImage_rejectsFalsifiedContentType() throws Exception {
            Integer typeId = createSimpleTypeAs(ownerToken, "Tool", false);
            Integer attrId = firstAttributeIdOfType(typeId);
            Integer pieceId = createPieceAs(ownerToken, typeId, "Hammer", attrId, "red");

            // Plain text payload masquerading as a PNG — the client declares image/png but the
            // bytes have no image magic, so magic-byte detection rejects it.
            byte[] fakePng = "MZ    ".getBytes(StandardCharsets.US_ASCII);
            MockMultipartFile attack = new MockMultipartFile(
                    "file", "payload.exe", "image/png", fakePng);
            mockMvc.perform(multipart("/organizations/" + orgSlug + "/pieces/" + pieceId + "/attachments")
                            .file(attack)
                            .param("kind", "IMAGE")
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("upload.invalid_kind"));
        }

        @Test
        @DisplayName("SVG uploads are rejected because they can embed JavaScript (XSS)")
        void uploadImage_rejectsSvg() throws Exception {
            Integer typeId = createSimpleTypeAs(ownerToken, "Tool", false);
            Integer attrId = firstAttributeIdOfType(typeId);
            Integer pieceId = createPieceAs(ownerToken, typeId, "Hammer", attrId, "red");

            String svg = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\">"
                    + "<script>alert(1)</script></svg>";
            MockMultipartFile svgFile = new MockMultipartFile(
                    "file", "logo.svg", "image/png", svg.getBytes(StandardCharsets.UTF_8));
            mockMvc.perform(multipart("/organizations/" + orgSlug + "/pieces/" + pieceId + "/attachments")
                            .file(svgFile)
                            .param("kind", "IMAGE")
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("upload.invalid_kind"));
        }

        @Test
        @DisplayName("decompression-bomb dimensions are rejected (width > 16384)")
        void uploadImage_rejectsOversizedDimensions() throws Exception {
            Integer typeId = createSimpleTypeAs(ownerToken, "Tool", false);
            Integer attrId = firstAttributeIdOfType(typeId);
            Integer pieceId = createPieceAs(ownerToken, typeId, "Hammer", attrId, "red");

            // 1-bit grayscale → tiny on disk but 16385 pixels wide, exceeding the dimension cap.
            byte[] bomb = imageBytes("png", 16_385, 1, BufferedImage.TYPE_BYTE_BINARY);
            MockMultipartFile bombFile = new MockMultipartFile("file", "bomb.png", "image/png", bomb);
            mockMvc.perform(multipart("/organizations/" + orgSlug + "/pieces/" + pieceId + "/attachments")
                            .file(bombFile)
                            .param("kind", "IMAGE")
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("upload.image_dimensions_too_large"));
        }

        @Test
        @DisplayName("DOCUMENT download redirects with response-content-disposition=attachment")
        void downloadDocument_setsAttachmentDisposition() throws Exception {
            Integer typeId = createSimpleTypeAs(ownerToken, "Tool", false);
            Integer attrId = firstAttributeIdOfType(typeId);
            Integer pieceId = createPieceAs(ownerToken, typeId, "Hammer", attrId, "red");

            MockMultipartFile pdf = new MockMultipartFile("file", "manual.pdf",
                    "application/pdf", "%PDF-1.4\n%%EOF".getBytes(StandardCharsets.US_ASCII));
            MvcResult r = mockMvc.perform(multipart("/organizations/" + orgSlug + "/pieces/" + pieceId + "/attachments")
                            .file(pdf)
                            .param("kind", "DOCUMENT")
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isCreated())
                    .andReturn();
            Integer attachmentId = (Integer) om.readValue(r.getResponse().getContentAsString(), Map.class).get("id");

            MvcResult download = mockMvc.perform(get("/organizations/" + orgSlug + "/pieces/" + pieceId
                            + "/attachments/" + attachmentId + "/download")
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isFound())
                    .andExpect(header().exists("Location"))
                    .andReturn();
            String location = download.getResponse().getHeader("Location");
            org.assertj.core.api.Assertions.assertThat(location)
                    .as("presigned URL must force attachment disposition for DOCUMENT downloads")
                    .contains("response-content-disposition=")
                    .contains("attachment");
        }

        @Test
        @DisplayName("SPECTATOR cannot upload but can download")
        void spectator_cannotUpload_butCanDownload() throws Exception {
            Integer typeId = createSimpleTypeAs(ownerToken, "Tool", false);
            Integer attrId = firstAttributeIdOfType(typeId);
            Integer pieceId = createPieceAs(ownerToken, typeId, "Hammer", attrId, "red");

            MockMultipartFile jpg = new MockMultipartFile("file", "p.jpg", "image/jpeg", jpegBytes(1, 1));
            MvcResult r = mockMvc.perform(multipart("/organizations/" + orgSlug + "/pieces/" + pieceId + "/attachments")
                            .file(jpg)
                            .param("kind", "IMAGE")
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isCreated())
                    .andReturn();
            Integer attachmentId = (Integer) om.readValue(r.getResponse().getContentAsString(), Map.class).get("id");

            mockMvc.perform(multipart("/organizations/" + orgSlug + "/pieces/" + pieceId + "/attachments")
                            .file(jpg)
                            .param("kind", "IMAGE")
                            .header("Authorization", "Bearer " + spectatorToken))
                    .andExpect(status().isForbidden());

            mockMvc.perform(get("/organizations/" + orgSlug + "/pieces/" + pieceId
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
            MvcResult r = mockMvc.perform(post("/organizations/" + orgSlug + "/pieces")
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
            mockMvc.perform(patch("/organizations/" + orgSlug + "/pieces/" + pieceId)
                            .header("Authorization", "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of(
                                    "attributeValues", List.of(Map.of("attributeId", attrId, "value", "red"))
                            ))))
                    .andExpect(status().isOk());

            // Upload an attachment
            MockMultipartFile jpg = new MockMultipartFile("file", "p.jpg", "image/jpeg", jpegBytes(1, 1));
            mockMvc.perform(multipart("/organizations/" + orgSlug + "/pieces/" + pieceId + "/attachments")
                            .file(jpg)
                            .param("kind", "IMAGE")
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isCreated());

            mockMvc.perform(get("/organizations/" + orgSlug + "/pieces/" + pieceId + "/history")
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
            MvcResult heavyR = mockMvc.perform(post("/organizations/" + orgSlug + "/piece-types")
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
            MvcResult r = mockMvc.perform(post("/organizations/" + orgSlug + "/pieces")
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
            mockMvc.perform(patch("/organizations/" + orgSlug + "/pieces/" + pieceId)
                            .header("Authorization", "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of(
                                    "attributeValues", List.of(Map.of("attributeId", heavyAttrId, "value", "12"))
                            ))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ACTIVE"));
        }

        @Test
        @DisplayName("create with empty pieceTypeIds is allowed and yields ACTIVE status")
        void emptyTypes_allowed_andActive() throws Exception {
            mockMvc.perform(post("/organizations/" + orgSlug + "/pieces")
                            .header("Authorization", "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of(
                                    "name", "NoType",
                                    "pieceTypeIds", List.of()
                            ))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.pieceTypes.length()").value(0))
                    .andExpect(jsonPath("$.status").value("ACTIVE"));
        }

        @Test
        @DisplayName("create with no pieceTypeIds field at all is allowed")
        void missingTypes_allowed() throws Exception {
            mockMvc.perform(post("/organizations/" + orgSlug + "/pieces")
                            .header("Authorization", "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of(
                                    "name", "Untyped"
                            ))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.pieceTypes.length()").value(0));
        }

        @Test
        @DisplayName("attribute value targeting an attribute outside the piece's types returns 400")
        void attribute_outsideTypes_returns400() throws Exception {
            Integer toolType = createSimpleTypeAs(ownerToken, "Tool", false);
            Integer otherType = createSimpleTypeAs(ownerToken, "Other", false);
            Integer otherAttr = firstAttributeIdOfType(otherType);

            mockMvc.perform(post("/organizations/" + orgSlug + "/pieces")
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
            mockMvc.perform(patch("/organizations/" + orgSlug + "/pieces/" + pieceId)
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
            mockMvc.perform(get("/organizations/" + orgSlug + "/pieces/" + pieceId + "/history")
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
            mockMvc.perform(post("/organizations/" + orgSlug + "/pieces")
                            .header("Authorization", "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of(
                                    "name", "AandB",
                                    "pieceTypeIds", List.of(typeA, typeB)
                            ))))
                    .andExpect(status().isCreated());

            // Filter by typeB → only the multi-type piece
            mockMvc.perform(get("/organizations/" + orgSlug + "/pieces?typeId=" + typeB)
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.content[0].name").value("AandB"));

            // Filter by typeA → both
            mockMvc.perform(get("/organizations/" + orgSlug + "/pieces?typeId=" + typeA)
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

            mockMvc.perform(patch("/organizations/" + orgSlug + "/members/" + memberId)
                            .header("Authorization", "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("role", "SPECTATOR"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.role").value("SPECTATOR"));
        }

        @Test
        @DisplayName("MANAGER can invite a SPECTATOR")
        void managerCanInviteSpectator() throws Exception {
            mockMvc.perform(post("/organizations/" + orgSlug + "/invitations")
                            .header("Authorization", "Bearer " + managerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of(
                                    "email", "newspect@test.com",
                                    "role", "SPECTATOR"
                            ))))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("Cross-org piece integrity under slug change/reuse")
    class CrossOrgPieceIntegrity {

        @Test
        @DisplayName("piece is accessible through the org's new slug after rename")
        void pieceAccessibleByCurrentSlug_afterRename() throws Exception {
            Integer typeId = createSimpleTypeAs(ownerToken, "Box", false);
            Integer pieceId = createPieceAs(ownerToken, typeId, "P-rename", null, null);

            mockMvc.perform(patch("/organizations/" + orgSlug)
                            .header("Authorization", "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("slug", "acme-v2"))))
                    .andExpect(status().isOk());

            mockMvc.perform(get("/organizations/acme-v2/pieces/" + pieceId)
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(pieceId));
        }

        @Test
        @DisplayName("piece is NOT accessible through the historical slug (frontend redirects via by-slug)")
        void pieceNotAccessibleThroughHistoricalSlug() throws Exception {
            Integer typeId = createSimpleTypeAs(ownerToken, "Box", false);
            Integer pieceId = createPieceAs(ownerToken, typeId, "P-historic", null, null);

            mockMvc.perform(patch("/organizations/" + orgSlug)
                            .header("Authorization", "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("slug", "acme-v2"))))
                    .andExpect(status().isOk());

            // The piece endpoint is path-scoped to the CURRENT slug — historical "acme" is no
            // longer a current slug, so @PreAuthorize on canReadOrgContent fails (403). The
            // frontend middleware would call /organizations/by-slug/acme first and redirect.
            mockMvc.perform(get("/organizations/" + orgSlug + "/pieces/" + pieceId)
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("after soft-delete, the piece is no longer reachable via the released slug")
        void pieceUnreachable_afterOrgSoftDelete() throws Exception {
            Integer typeId = createSimpleTypeAs(ownerToken, "Box", false);
            Integer pieceId = createPieceAs(ownerToken, typeId, "P-gone", null, null);

            mockMvc.perform(delete("/organizations/" + orgSlug)
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isNoContent());

            // The slug "acme" is no longer current (the row was renamed to a `__deleted__`
            // marker), so authorization fails before the piece lookup. The deleted org's piece
            // is therefore unreachable via the released slug, even by a global ADMIN.
            mockMvc.perform(get("/organizations/" + orgSlug + "/pieces/" + pieceId)
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isForbidden());
        }
    }
}
