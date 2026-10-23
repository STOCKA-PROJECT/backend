package com.stocka.backend.modules.mcp;

import static com.stocka.backend.modules.organizations.IntegrationTestSupport.ADMIN_EMAIL;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.ADMIN_PASSWORD;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.buildMockMvc;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.login;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;

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

import com.stocka.backend.modules.oauth.OAuthTestClient;
import com.stocka.backend.modules.organizations.IntegrationTestSupport;

import tools.jackson.databind.JsonNode;

/**
 * The MCP tools end to end: a real OAuth token, JSON-RPC over {@code /mcp}, the services behind.
 * Fixture: the seeded admin owns organization {@code acme} (so hardware features are enabled).
 */
@SpringBootTest
@DisplayName("MCP tools (integration)")
class McpToolsIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;
    private OAuthTestClient oauth;
    private String token;
    private String spaToken;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = buildMockMvc(context);
        IntegrationTestSupport.cleanDatabase(jdbcTemplate);
        oauth = new OAuthTestClient(mockMvc);
        spaToken = login(mockMvc, new com.fasterxml.jackson.databind.ObjectMapper(), ADMIN_EMAIL, ADMIN_PASSWORD);
        mockMvc.perform(post("/organizations")
                        .header("Authorization", "Bearer " + spaToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Acme\",\"slug\":\"acme\"}"))
                .andExpect(status().isOk());
        token = oauth.connectWithAllScopes().accessToken();
    }

    private int createType(String name, String kind, String attributesJson) throws Exception {
        String kindJson = kind == null ? "" : ",\"kind\":\"" + kind + "\"";
        JsonNode type = oauth.ok(token, "piece_type_create",
                "{\"org\":\"acme\",\"name\":\"" + name + "\"" + kindJson + ",\"attributes\":" + attributesJson + "}");
        return type.path("id").asInt();
    }

    @Nested
    @DisplayName("catalog")
    class Catalog {

        @Test
        @DisplayName("tools/list publishes every domain with schemas; resources and prompts are listed")
        void lists() throws Exception {
            JsonNode tools = oauth.mcp(token, "tools/list", "{}").path("result").path("tools");
            List<String> names = new ArrayList<>();
            tools.forEach(t -> names.add(t.path("name").asText()));
            assertThat(names).contains("me", "org_get", "locations_tree", "piece_types_list", "pieces_search", "piece_create",
                    "contacts_list", "pieces_import", "ports_list", "timelines_list", "timeline_scene_edit", "search_reindex");
            assertThat(names).hasSizeGreaterThanOrEqualTo(50);
            JsonNode edit = null;
            for (JsonNode t : tools) {
                if (t.path("name").asText().equals("timeline_scene_edit")) edit = t;
            }
            assertThat(edit).isNotNull();
            assertThat(edit.path("inputSchema").path("properties").path("ops").path("items").path("oneOf").size()).isEqualTo(16);
            assertThat(edit.path("annotations").path("readOnlyHint").asBoolean()).isFalse();
            assertThat(edit.path("inputSchema").path("required").toString()).contains("org");

            JsonNode resources = oauth.mcp(token, "resources/list", "{}").path("result").path("resources");
            List<String> uris = new ArrayList<>();
            resources.forEach(r -> uris.add(r.path("uri").asText()));
            assertThat(uris).contains("stocka://guide", "stocka://catalog", "stocka://schema/scene");
            JsonNode guideResponse = oauth.mcp(token, "resources/read", "{\"uri\":\"stocka://guide\"}");
            assertThat(guideResponse.has("result")).as(guideResponse.toString()).isTrue();
            assertThat(guideResponse.path("result").path("contents").get(0).path("text").asText()).contains("Stocka");
            JsonNode overviewResponse = oauth.mcp(token, "resources/read", "{\"uri\":\"stocka://org/acme/overview\"}");
            assertThat(overviewResponse.has("result")).as(overviewResponse.toString()).isTrue();
            assertThat(overviewResponse.path("result").path("contents").get(0).path("text").asText()).contains("Acme");

            JsonNode prompts = oauth.mcp(token, "prompts/list", "{}").path("result").path("prompts");
            assertThat(prompts.size()).isEqualTo(3);
            JsonNode prompt = oauth.mcp(token, "prompts/get",
                    "{\"name\":\"timeline_from_description\",\"arguments\":{\"org\":\"acme\",\"description\":\"llums\"}}")
                    .path("result");
            assertThat(prompt.path("messages").get(0).path("content").path("text").asText()).contains("acme").contains("llums");
        }
    }

    @Nested
    @DisplayName("inventory")
    class Inventory {

        @Test
        @DisplayName("types, pieces, locations, search, history and deletion through the tools")
        void lifecycle() throws Exception {
            int typeId = createType("Figura", null,
                    "[{\"name\":\"color\",\"displayName\":\"Color\",\"type\":\"TEXT\",\"required\":true}]");
            JsonNode types = oauth.ok(token, "piece_types_list", "{\"org\":\"acme\"}");
            assertThat(types.path("items").get(0).path("attributes").get(0).path("displayName").asText()).isEqualTo("Color");

            JsonNode root = oauth.ok(token, "location_create", "{\"org\":\"acme\",\"name\":\"Magatzem\"}");
            JsonNode shelf = oauth.ok(token, "location_create",
                    "{\"org\":\"acme\",\"name\":\"Estant\",\"parentId\":" + root.path("id").asInt() + "}");
            assertThat(shelf.path("path").asText()).isEqualTo("Magatzem / Estant");

            JsonNode pending = oauth.ok(token, "piece_create",
                    "{\"org\":\"acme\",\"name\":\"Pastor\",\"pieceTypeIds\":[" + typeId + "]}");
            assertThat(pending.path("status").asText()).isEqualTo("PENDING");
            int pieceId = pending.path("id").asInt();

            JsonNode active = oauth.ok(token, "piece_update", "{\"org\":\"acme\",\"pieceId\":" + pieceId
                    + ",\"locationId\":" + shelf.path("id").asInt()
                    + ",\"attributeValues\":[{\"attribute\":\"Color\",\"value\":\"marró\"}]}");
            assertThat(active.path("status").asText()).isEqualTo("ACTIVE");
            assertThat(active.path("locationPath").asText()).isEqualTo("Magatzem / Estant");
            assertThat(active.path("attributeValues").get(0).path("value").asText()).isEqualTo("marró");

            JsonNode tree = oauth.ok(token, "locations_tree", "{\"org\":\"acme\"}");
            assertThat(tree.path("items").get(1).path("pieceCount").asInt()).isEqualTo(1);

            JsonNode found = oauth.ok(token, "pieces_search", "{\"org\":\"acme\",\"q\":\"pastor\",\"size\":10}");
            assertThat(found.path("total").asInt()).isEqualTo(1);
            assertThat(found.path("semantic").asBoolean()).isFalse();
            assertThat(oauth.ok(token, "pieces_search", "{\"org\":\"acme\",\"status\":\"PENDING\"}").path("total").asInt()).isZero();

            JsonNode history = oauth.ok(token, "piece_history", "{\"org\":\"acme\",\"pieceId\":" + pieceId + "}");
            assertThat(history.path("total").asInt()).isGreaterThanOrEqualTo(2);

            JsonNode ambiguous = oauth.error(token, "piece_update", "{\"org\":\"acme\",\"pieceId\":" + pieceId
                    + ",\"attributeValues\":[{\"attribute\":\"nope\",\"value\":\"x\"}]}");
            assertThat(ambiguous.path("error").asText()).isEqualTo("pieces.attribute_unknown");
            assertThat(ambiguous.path("hint").asText()).contains("piece_types_list");

            JsonNode csv = oauth.call(token, "pieces_export", "{\"org\":\"acme\"}");
            assertThat(csv.path("content").get(0).path("text").asText()).contains("Pastor");

            oauth.ok(token, "piece_delete", "{\"org\":\"acme\",\"pieceId\":" + pieceId + "}");
            JsonNode gone = oauth.error(token, "piece_get", "{\"org\":\"acme\",\"pieceId\":" + pieceId + "}");
            assertThat(gone.path("error").asText()).isEqualTo("pieces.not_found");
            assertThat(gone.path("hint").asText()).contains("pieces_search");
        }

        @Test
        @DisplayName("unknown organizations, bad arguments and foreign slugs are readable errors")
        void errors() throws Exception {
            JsonNode unknown = oauth.error(token, "org_get", "{\"org\":\"nope\"}");
            assertThat(unknown.path("error").asText()).isEqualTo("organizations.not_found");
            assertThat(unknown.path("hint").asText()).contains("me");
            // Arguments that do not match the input schema are refused by the MCP SDK itself, with a readable text.
            JsonNode bad = oauth.call(token, "piece_get", "{\"org\":\"acme\",\"pieceId\":\"abc\"}");
            assertThat(bad.path("isError").asBoolean()).isTrue();
            assertThat(bad.path("content").get(0).path("text").asText()).contains("pieceId");
            JsonNode me = oauth.ok(token, "me", "{}");
            assertThat(me.path("organizations").get(0).path("hardwareEnabled").asBoolean()).isTrue();
            JsonNode org = oauth.ok(token, "org_get", "{\"org\":\"acme\"}");
            assertThat(org.path("role").asText()).isEqualTo("OWNER");
            assertThat(org.path("usage").path("maxPieces").asLong()).isPositive();
        }
    }

    @Nested
    @DisplayName("timelines")
    class Timelines {

        @Test
        @DisplayName("two batches: items first, then clips using the created ids")
        void batches() throws Exception {
            int typeId = createType("Llum", "relay", "[]");
            oauth.ok(token, "piece_type_action_create", "{\"org\":\"acme\",\"typeId\":" + typeId
                    + ",\"name\":\"turn_on\",\"displayName\":\"Encendre\",\"parameters\":[{\"name\":\"time\","
                    + "\"displayName\":\"Temps\",\"type\":\"DECIMAL\",\"required\":true,\"dynamic\":true,\"isDuration\":true}]}");
            oauth.ok(token, "piece_create", "{\"org\":\"acme\",\"name\":\"Focus\",\"pieceTypeIds\":[" + typeId + "]}");
            int timelineId = oauth.ok(token, "timeline_create", "{\"org\":\"acme\",\"name\":\"Nadal\"}").path("id").asInt();

            JsonNode first = oauth.ok(token, "timeline_scene_edit", "{\"org\":\"acme\",\"timelineId\":" + timelineId
                    + ",\"expectedVersion\":1,\"ops\":[{\"op\":\"add_item\",\"pieceName\":\"Focus\"}]}");
            assertThat(first.path("version").asInt()).isEqualTo(2);
            String itemId = first.path("createdIds").get(0).asText();
            assertThat(itemId).startsWith("item-");
            assertThat(first.path("scene").path("items").get(0).path("pieceName").asText()).isEqualTo("Focus");

            JsonNode second = oauth.ok(token, "timeline_scene_edit", "{\"org\":\"acme\",\"timelineId\":" + timelineId
                    + ",\"expectedVersion\":2,\"ops\":[{\"op\":\"add_clip\",\"itemId\":\"" + itemId
                    + "\",\"action\":\"turn_on\",\"startMs\":1000,\"durationMs\":3000},"
                    + "{\"op\":\"set_duration\",\"durationMs\":10000}]}");
            assertThat(second.path("version").asInt()).isEqualTo(3);
            assertThat(second.path("errors").asInt()).isZero();
            JsonNode clip = second.path("scene").path("clips").get(0);
            assertThat(clip.path("action").asText()).isEqualTo("turn_on");
            assertThat(clip.path("startMs").asLong()).isEqualTo(1000);
            List<String> codes = new ArrayList<>();
            second.path("issues").forEach(i -> codes.add(i.path("code").asText()));
            assertThat(codes).contains("W_NO_PORT");

            JsonNode stale = oauth.error(token, "timeline_scene_edit", "{\"org\":\"acme\",\"timelineId\":" + timelineId
                    + ",\"expectedVersion\":2,\"ops\":[{\"op\":\"set_duration\",\"durationMs\":5000}]}");
            assertThat(stale.path("error").asText()).isEqualTo("timeline_scenes.version_conflict");
            assertThat(stale.path("hint").asText()).contains("timeline_scene_get");
            assertThat(stale.path("params").path("currentVersion").asInt()).isEqualTo(3);

            JsonNode badOp = oauth.error(token, "timeline_scene_edit", "{\"org\":\"acme\",\"timelineId\":" + timelineId
                    + ",\"expectedVersion\":3,\"ops\":[{\"op\":\"add_clip\",\"itemId\":\"" + itemId + "\",\"action\":\"explode\"}]}");
            assertThat(badOp.path("error").asText()).isEqualTo("timeline_scenes.op_invalid");
            assertThat(badOp.path("params").path("candidates").toString()).contains("turn_on");

            JsonNode validation = oauth.ok(token, "timeline_validate", "{\"org\":\"acme\",\"timelineId\":" + timelineId + "}");
            assertThat(validation.path("valid").asBoolean()).isTrue();
            assertThat(validation.path("warnings").asInt()).isGreaterThanOrEqualTo(1);

            JsonNode raw = oauth.ok(token, "timeline_scene_get", "{\"org\":\"acme\",\"timelineId\":" + timelineId + ",\"format\":\"raw\"}");
            assertThat(raw.path("document").path("clips").size()).isEqualTo(1);
            assertThat(raw.path("version").asInt()).isEqualTo(3);

            // The editor saves on top (version 3 → 4); the next MCP edit must re-read.
            mockMvc.perform(put("/organizations/acme/timelines/" + timelineId + "/scene")
                            .header("Authorization", "Bearer " + spaToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"schemaVersion\":1,\"version\":3,\"document\":" + raw.path("document").toString() + "}"))
                    .andExpect(status().isOk());
            JsonNode listed = oauth.ok(token, "timelines_list", "{\"org\":\"acme\"}");
            assertThat(listed.path("items").get(0).path("version").asInt()).isEqualTo(4);
            assertThat(listed.path("items").get(0).path("clips").asInt()).isEqualTo(1);

            JsonNode detail = oauth.ok(token, "timeline_get", "{\"org\":\"acme\",\"timelineId\":" + timelineId + "}");
            assertThat(detail.path("scene").path("clips").get(0).path("pieceName").asText()).isEqualTo("Focus");

            oauth.ok(token, "timeline_delete", "{\"org\":\"acme\",\"timelineId\":" + timelineId + "}");
            assertThat(oauth.ok(token, "timelines_list", "{\"org\":\"acme\"}").path("items").size()).isZero();
        }
    }
}
