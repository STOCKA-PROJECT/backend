package com.stocka.backend.modules.pieces.importexport;

import static com.stocka.backend.modules.organizations.IntegrationTestSupport.ADMIN_EMAIL;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.ADMIN_PASSWORD;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.buildMockMvc;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.login;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.signupAndLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.assertj.core.api.Assertions;
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
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.web.context.WebApplicationContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocka.backend.modules.organizations.IntegrationTestSupport;

/**
 * End-to-end coverage of the piece import/export feature. Reuses the Acme fixture conventions of
 * {@code PiecesFeatureIntegrationTest}: an ADMIN owner plus MANAGER/USER/SPECTATOR members and an
 * outsider. Each {@code @Nested} class focuses on one slice.
 */
@SpringBootTest
@DisplayName("Piece import/export (integration)")
class PieceImportExportIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final ObjectMapper om = new ObjectMapper();

    private MockMvc mockMvc;
    private String ownerToken;
    private String userToken;
    private String spectatorToken;
    private String outsiderToken;
    private Integer orgId;
    private String orgSlug;
    private Integer managerUserId;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = buildMockMvc(context);
        IntegrationTestSupport.cleanDatabase(jdbcTemplate);

        ownerToken = login(mockMvc, om, ADMIN_EMAIL, ADMIN_PASSWORD);
        orgId = createOrgAsOwner();

        signupAndLogin(mockMvc, om, jdbcTemplate, "manager@test.com", "manager");
        userToken = signupAndLogin(mockMvc, om, jdbcTemplate, "user@test.com", "userp");
        spectatorToken = signupAndLogin(mockMvc, om, jdbcTemplate, "spect@test.com", "spect");
        outsiderToken = signupAndLogin(mockMvc, om, jdbcTemplate, "out@test.com", "outsider");

        managerUserId = userId("manager@test.com");
        addMember(orgId, managerUserId, "MANAGER");
        addMember(orgId, userId("user@test.com"), "USER");
        addMember(orgId, userId("spect@test.com"), "SPECTATOR");
    }

    // ---------- helpers ----------

    private Integer userId(String email) {
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", Integer.class, email);
    }

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

    private void addMember(Integer orgId, Integer userId, String role) {
        Long nextId = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(id), 0) + 1 FROM organization_members", Long.class);
        jdbcTemplate.update(
                "INSERT INTO organization_members (id, user_id, organization_id, role, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                nextId, userId, orgId, role);
    }

    private Integer createType(String typeName, String attrName, String attrDisplay,
                               String attrType, boolean required) throws Exception {
        Map<String, Object> attr = Map.of(
                "name", attrName, "displayName", attrDisplay, "type", attrType, "required", required);
        Map<String, Object> body = Map.of("name", typeName, "attributes", List.of(attr));
        MvcResult r = mockMvc.perform(post("/organizations/" + orgSlug + "/piece-types")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        return (Integer) om.readValue(r.getResponse().getContentAsString(), Map.class).get("id");
    }

    private Integer createLocation(String name) throws Exception {
        MvcResult r = mockMvc.perform(post("/organizations/" + orgSlug + "/locations")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("name", name))))
                .andExpect(status().isCreated())
                .andReturn();
        return (Integer) om.readValue(r.getResponse().getContentAsString(), Map.class).get("id");
    }

    private Integer firstAttributeId(Integer typeId) throws Exception {
        MvcResult r = mockMvc.perform(get("/organizations/" + orgSlug + "/piece-types/" + typeId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andReturn();
        Map<?, ?> body = om.readValue(r.getResponse().getContentAsString(), Map.class);
        List<?> attrs = (List<?>) body.get("attributes");
        return (Integer) ((Map<?, ?>) attrs.get(0)).get("id");
    }

    private Integer createPiece(Integer typeId, String name, String serial, Integer attrId, String value)
            throws Exception {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("name", name);
        body.put("serialNumber", serial);
        body.put("pieceTypeIds", List.of(typeId));
        body.put("attributeValues", List.of(Map.of("attributeId", attrId, "value", value)));
        MvcResult r = mockMvc.perform(post("/organizations/" + orgSlug + "/pieces")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        return (Integer) om.readValue(r.getResponse().getContentAsString(), Map.class).get("id");
    }

    private String exportCsv(String token, String query) throws Exception {
        MvcResult r = mockMvc.perform(get("/organizations/" + orgSlug + "/pieces/export?format=csv" + query)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private long pieceCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pieces WHERE organization_id = ? AND deleted_at IS NULL",
                Long.class, orgId);
    }

    // ---------- suites ----------

    @Nested
    @DisplayName("Export")
    class Export {

        @Test
        @DisplayName("CSV export contains fixed headers, attribute column and piece values")
        void export_containsHeadersAndValues() throws Exception {
            Integer typeId = createType("Tool", "color", "Color", "TEXT", true);
            Integer attrId = firstAttributeId(typeId);
            createPiece(typeId, "Hammer", "SN-1", attrId, "red");

            String csv = exportCsv(ownerToken, "");
            Assertions.assertThat(csv)
                    .contains("name").contains("serial_number").contains("owner_email")
                    .contains("location_path").contains("piece_types").contains("Tool / Color")
                    .contains("Hammer").contains("SN-1").contains("red");
        }

        @Test
        @DisplayName("export honors advanced attr filters, excluding non-matching rows")
        void export_respectsAttributeFilter() throws Exception {
            Integer typeId = createType("Tool", "color", "Color", "TEXT", true);
            Integer attrId = firstAttributeId(typeId);
            createPiece(typeId, "Hammer", "SN-1", attrId, "red");
            createPiece(typeId, "Anvil", "SN-2", attrId, "blue");

            String csv = exportCsv(ownerToken, "&attr=TYPE:" + attrId + ":red");
            Assertions.assertThat(csv).contains("Hammer").doesNotContain("Anvil");
        }

        @Test
        @DisplayName("empty organization exports just the header row")
        void export_empty() throws Exception {
            String csv = exportCsv(ownerToken, "");
            Assertions.assertThat(csv).contains("name").contains("serial_number");
            // only the header line (plus trailing newline)
            Assertions.assertThat(csv.strip().lines().count()).isEqualTo(1);
        }

        @Test
        @DisplayName("SPECTATOR can export")
        void export_spectatorAllowed() throws Exception {
            createType("Tool", "color", "Color", "TEXT", false);
            mockMvc.perform(get("/organizations/" + orgSlug + "/pieces/export?format=csv")
                            .header("Authorization", "Bearer " + spectatorToken))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("XLSX export downloads with the spreadsheet content type")
        void export_xlsx() throws Exception {
            createType("Tool", "color", "Color", "TEXT", false);
            mockMvc.perform(get("/organizations/" + orgSlug + "/pieces/export?format=xlsx")
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isOk())
                    .andExpect(result -> Assertions.assertThat(
                            result.getResponse().getContentType())
                            .contains("spreadsheetml"));
        }
    }

    @Nested
    @DisplayName("Template")
    class Template {

        @Test
        @DisplayName("template exposes fixed and attribute columns; requires write access")
        void template_columnsAndAuth() throws Exception {
            createType("Tool", "color", "Color", "TEXT", true);

            MvcResult r = mockMvc.perform(get("/organizations/" + orgSlug + "/pieces/import/template?format=csv")
                            .header("Authorization", "Bearer " + userToken))
                    .andExpect(status().isOk())
                    .andReturn();
            Assertions.assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8))
                    .contains("owner_email").contains("Tool / Color");

            mockMvc.perform(get("/organizations/" + orgSlug + "/pieces/import/template?format=csv")
                            .header("Authorization", "Bearer " + spectatorToken))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("Import dry-run")
    class ImportDryRun {

        @Test
        @DisplayName("validates a valid file and writes nothing")
        void dryRun_valid_writesNothing() throws Exception {
            createType("Tool", "color", "Color", "TEXT", true);
            String csv = "name,serial_number,piece_types,Tool / Color\n"
                    + "Hammer,SN-1,Tool,red\n"
                    + "Anvil,SN-2,Tool,blue\n";

            mockMvc.perform(importBuilder(ownerToken, csv, "create", true))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.dryRun").value(true))
                    .andExpect(jsonPath("$.applied").value(false))
                    .andExpect(jsonPath("$.created").value(2))
                    .andExpect(jsonPath("$.failed").value(0));

            Assertions.assertThat(pieceCount()).isZero();
        }

        @Test
        @DisplayName("reports per-row errors but still answers 200")
        void dryRun_errors_returns200() throws Exception {
            String csv = "name,piece_types\nX,NoSuchType\n";
            mockMvc.perform(importBuilder(ownerToken, csv, "create", true))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.failed").value(1))
                    .andExpect(jsonPath("$.rows[0].action").value("ERROR"));
            Assertions.assertThat(pieceCount()).isZero();
        }
    }

    @Nested
    @DisplayName("Import commit")
    class ImportCommit {

        @Test
        @DisplayName("creates pieces with attribute, owner and location resolved by name")
        void commit_create_resolvesReferences() throws Exception {
            createType("Tool", "color", "Color", "TEXT", true);
            createLocation("Warehouse");
            String csv = "name,serial_number,piece_types,owner_email,location_path,Tool / Color\n"
                    + "Hammer,SN-1,Tool,manager@test.com,Warehouse,red\n";

            mockMvc.perform(importBuilder(ownerToken, csv, "create", false))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.applied").value(true))
                    .andExpect(jsonPath("$.created").value(1))
                    .andExpect(jsonPath("$.rows[0].pieceId").isNumber());

            Assertions.assertThat(pieceCount()).isEqualTo(1);
            // The created piece carries the resolved owner and is ACTIVE (required attr filled).
            mockMvc.perform(get("/organizations/" + orgSlug + "/pieces")
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.content[0].ownerUserId").value(managerUserId))
                    .andExpect(jsonPath("$.content[0].status").value("ACTIVE"));
        }

        @Test
        @DisplayName("a single invalid row rejects the whole import with 422 and writes nothing")
        void commit_invalidRow_rejectsAll() throws Exception {
            createType("Tool", "color", "Color", "TEXT", true);
            String csv = "name,serial_number,piece_types,Tool / Color\n"
                    + "Good,SN-1,Tool,red\n"
                    + "Bad,SN-2,NoSuchType,blue\n";

            mockMvc.perform(importBuilder(ownerToken, csv, "create", false))
                    .andExpect(status().is(422))
                    .andExpect(jsonPath("$.applied").value(false));

            Assertions.assertThat(pieceCount()).isZero();
        }

        @Test
        @DisplayName("invalid attribute value rejects the import")
        void commit_invalidAttribute_rejected() throws Exception {
            createType("Heavy", "weight", "Weight", "INTEGER", true);
            String csv = "name,piece_types,Heavy / Weight\n"
                    + "Bad,Heavy,not-a-number\n";

            mockMvc.perform(importBuilder(ownerToken, csv, "create", false))
                    .andExpect(status().is(422));
            Assertions.assertThat(pieceCount()).isZero();
        }
    }

    @Nested
    @DisplayName("Upsert")
    class Upsert {

        @Test
        @DisplayName("upsert updates the existing piece matched by serial and does not duplicate")
        void upsert_updatesExisting() throws Exception {
            createType("Tool", "color", "Color", "TEXT", true);
            String createCsv = "name,serial_number,piece_types,Tool / Color\nHammer,SN-1,Tool,red\n";
            mockMvc.perform(importBuilder(ownerToken, createCsv, "create", false))
                    .andExpect(status().isOk());
            Assertions.assertThat(pieceCount()).isEqualTo(1);

            String upsertCsv = "name,serial_number,Tool / Color\nHammerV2,SN-1,green\n";
            mockMvc.perform(importBuilder(ownerToken, upsertCsv, "upsert", false))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.updated").value(1))
                    .andExpect(jsonPath("$.created").value(0));

            Assertions.assertThat(pieceCount()).isEqualTo(1);
            mockMvc.perform(get("/organizations/" + orgSlug + "/pieces")
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(jsonPath("$.content[0].name").value("HammerV2"));
        }

        @Test
        @DisplayName("upsert creates when the serial does not exist yet")
        void upsert_createsWhenNew() throws Exception {
            createType("Tool", "color", "Color", "TEXT", true);
            String csv = "name,serial_number,piece_types,Tool / Color\nNew,SN-NEW,Tool,red\n";
            mockMvc.perform(importBuilder(ownerToken, csv, "upsert", false))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.created").value(1))
                    .andExpect(jsonPath("$.updated").value(0));
            Assertions.assertThat(pieceCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("an exported file re-imported with upsert round-trips without duplicating")
        void export_then_upsert_roundTrip() throws Exception {
            Integer typeId = createType("Tool", "color", "Color", "TEXT", true);
            Integer attrId = firstAttributeId(typeId);
            createPiece(typeId, "Hammer", "SN-1", attrId, "red");
            createPiece(typeId, "Anvil", "SN-2", attrId, "blue");
            Assertions.assertThat(pieceCount()).isEqualTo(2);

            String exported = exportCsv(ownerToken, "");
            mockMvc.perform(importBuilder(ownerToken, exported, "upsert", false))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.updated").value(2))
                    .andExpect(jsonPath("$.created").value(0));
            Assertions.assertThat(pieceCount()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("Authorization")
    class Authorization {

        @Test
        @DisplayName("USER can import; SPECTATOR and outsiders cannot")
        void import_permissions() throws Exception {
            createType("Tool", "color", "Color", "TEXT", false);
            String csv = "name,piece_types\nP,Tool\n";

            mockMvc.perform(importBuilder(userToken, csv, "create", true)).andExpect(status().isOk());
            mockMvc.perform(importBuilder(spectatorToken, csv, "create", true)).andExpect(status().isForbidden());
            mockMvc.perform(importBuilder(outsiderToken, csv, "create", true)).andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("Validation")
    class Validation {

        @Test
        @DisplayName("owner that is not a member is reported as a row error")
        void ownerNotMember_error() throws Exception {
            createType("Tool", "color", "Color", "TEXT", false);
            String csv = "name,piece_types,owner_email\nP,Tool,out@test.com\n";
            mockMvc.perform(importBuilder(ownerToken, csv, "create", true))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.failed").value(1));
        }

        @Test
        @DisplayName("a serial repeated within the file is rejected")
        void duplicateSerialInFile_error() throws Exception {
            createType("Tool", "color", "Color", "TEXT", false);
            String csv = "name,serial_number,piece_types\nA,DUP,Tool\nB,DUP,Tool\n";
            mockMvc.perform(importBuilder(ownerToken, csv, "create", true))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.failed").value(1))
                    .andExpect(jsonPath("$.rows[1].action").value("ERROR"));
        }

        @Test
        @DisplayName("unknown columns are reported as warnings, not errors")
        void unknownColumn_warning() throws Exception {
            createType("Tool", "color", "Color", "TEXT", false);
            String csv = "name,piece_types,totally_unknown\nP,Tool,whatever\n";
            mockMvc.perform(importBuilder(ownerToken, csv, "create", true))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.failed").value(0))
                    .andExpect(jsonPath("$.warnings.length()").value(1));
        }

        @Test
        @DisplayName("a file with no header row is rejected with 400")
        void emptyFile_rejected() throws Exception {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "empty.csv", "text/csv", new byte[0]);
            mockMvc.perform(multipart("/organizations/" + orgSlug + "/pieces/import")
                            .file(file)
                            .param("format", "csv")
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isBadRequest());
        }
    }

    private MockMultipartHttpServletRequestBuilder importBuilder(String token, String csv,
                                                                 String mode, boolean dryRun) {
        MockMultipartFile file = new MockMultipartFile(
                "file", "import.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
        MockMultipartHttpServletRequestBuilder builder =
                multipart("/organizations/" + orgSlug + "/pieces/import").file(file);
        builder.param("format", "csv").param("dryRun", String.valueOf(dryRun));
        if (mode != null) {
            builder.param("mode", mode);
        }
        builder.header("Authorization", "Bearer " + token);
        return builder;
    }
}
