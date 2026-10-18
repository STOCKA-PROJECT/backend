package com.stocka.backend.modules.pieces;

import static com.stocka.backend.modules.organizations.IntegrationTestSupport.ADMIN_EMAIL;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.ADMIN_PASSWORD;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.buildMockMvc;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.login;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.WebApplicationContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocka.backend.modules.organizations.IntegrationTestSupport;
import com.stocka.backend.modules.pieces.service.PieceSearchRanker;

/**
 * How the piece listing and export use a {@link PieceSearchRanker}: ranked ids drive the order
 * and the pagination, the structural filters still apply, and an absent ranking falls back to the
 * {@code LIKE} filter. The ranker is mocked so no XEYE service is involved.
 *
 * <p>Fixture: Acme with one type whose attribute "Color" is required, and three pieces: Pastor
 * (ACTIVE), Ovella (PENDING, no value) and Estrella (ACTIVE).
 */
@SpringBootTest
@DisplayName("Piece listing with semantic ranking (integration)")
class PieceSemanticListIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockitoBean private PieceSearchRanker ranker;

    private final ObjectMapper om = new ObjectMapper();

    private MockMvc mockMvc;
    private String ownerToken;
    private String orgSlug;
    private Integer orgId;
    private Integer pastorId;
    private Integer ovellaId;
    private Integer estrellaId;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = buildMockMvc(context);
        IntegrationTestSupport.cleanDatabase(jdbcTemplate);
        ownerToken = login(mockMvc, om, ADMIN_EMAIL, ADMIN_PASSWORD);

        MvcResult created = mockMvc.perform(post("/organizations")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("name", "Acme", "slug", "acme"))))
                .andExpect(status().isOk())
                .andReturn();
        Map<?, ?> org = om.readValue(created.getResponse().getContentAsString(), Map.class);
        orgSlug = (String) org.get("slug");
        orgId = (Integer) org.get("id");

        Integer typeId = createType();
        Integer colorId = firstAttributeIdOfType(typeId);
        pastorId = createPiece("Pastor", typeId, colorId, "marró");
        ovellaId = createPiece("Ovella", typeId, null, null);
        estrellaId = createPiece("Estrella", typeId, colorId, "daurat");
    }

    // ---------- helpers ----------

    private Integer createType() throws Exception {
        Map<String, Object> attr = Map.of("name", "color", "displayName", "Color", "type", "TEXT", "required", true);
        MvcResult r = mockMvc.perform(post("/organizations/" + orgSlug + "/piece-types")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("name", "Figura", "attributes", List.of(attr)))))
                .andExpect(status().isCreated())
                .andReturn();
        return (Integer) om.readValue(r.getResponse().getContentAsString(), Map.class).get("id");
    }

    private Integer firstAttributeIdOfType(Integer typeId) throws Exception {
        MvcResult r = mockMvc.perform(get("/organizations/" + orgSlug + "/piece-types/" + typeId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andReturn();
        List<?> attrs = (List<?>) om.readValue(r.getResponse().getContentAsString(), Map.class).get("attributes");
        return (Integer) ((Map<?, ?>) attrs.get(0)).get("id");
    }

    private Integer createPiece(String name, Integer typeId, Integer attributeId, String value) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("pieceTypeIds", List.of(typeId));
        if (attributeId != null) {
            body.put("attributeValues", List.of(Map.of("attributeId", attributeId, "value", value)));
        }
        MvcResult r = mockMvc.perform(post("/organizations/" + orgSlug + "/pieces")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        return (Integer) om.readValue(r.getResponse().getContentAsString(), Map.class).get("id");
    }

    private List<Integer> listIds(String query) throws Exception {
        MvcResult r = mockMvc.perform(get("/organizations/" + orgSlug + "/pieces" + query)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andReturn();
        Map<?, ?> page = om.readValue(r.getResponse().getContentAsString(), Map.class);
        List<Integer> ids = new ArrayList<>();
        for (Object item : (List<?>) page.get("content")) {
            ids.add((Integer) ((Map<?, ?>) item).get("id"));
        }
        return ids;
    }

    private void rankerReturns(String term, List<Integer> ids) {
        when(ranker.rank(any(), eq(term))).thenReturn(Optional.of(ids));
    }

    @Nested
    @DisplayName("when the ranker answers")
    class Ranked {

        @Test
        @DisplayName("the page follows the ranking order and drops unknown ids")
        void order() throws Exception {
            rankerReturns("figura de fusta", List.of(estrellaId, pastorId, 999_999));

            // MockMvc encodes the template itself: spaces become %20, a literal %20 would be %2520.
            assertThat(listIds("?q=figura de fusta")).containsExactly(estrellaId, pastorId);
            mockMvc.perform(get("/organizations/" + orgSlug + "/pieces?q=figura de fusta")
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(jsonPath("$.totalElements").value(2));
        }

        @Test
        @DisplayName("the structural filters still apply on top of the ranking")
        void filters() throws Exception {
            rankerReturns("x", List.of(estrellaId, ovellaId, pastorId));

            assertThat(listIds("?q=x&status=PENDING")).containsExactly(ovellaId);
            assertThat(listIds("?q=x&status=ACTIVE")).containsExactly(estrellaId, pastorId);
        }

        @Test
        @DisplayName("pagination slices the ranked list")
        void pagination() throws Exception {
            rankerReturns("x", List.of(estrellaId, ovellaId, pastorId));

            assertThat(listIds("?q=x&size=1&page=1")).containsExactly(ovellaId);
            mockMvc.perform(get("/organizations/" + orgSlug + "/pieces?q=x&size=2&page=0")
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(jsonPath("$.totalElements").value(3))
                    .andExpect(jsonPath("$.totalPages").value(2))
                    .andExpect(jsonPath("$.content.length()").value(2));
        }

        @Test
        @DisplayName("a page past the end is empty but keeps the total")
        void pastEnd() throws Exception {
            rankerReturns("x", List.of(estrellaId));

            mockMvc.perform(get("/organizations/" + orgSlug + "/pieces?q=x&size=5&page=3")
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.content.length()").value(0));
        }

        @Test
        @DisplayName("an empty ranking yields an empty page instead of the LIKE fallback")
        void emptyRanking() throws Exception {
            rankerReturns("pastor", List.of());

            mockMvc.perform(get("/organizations/" + orgSlug + "/pieces?q=pastor")
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(0));
        }

        @Test
        @DisplayName("the ranker receives the organization and the trimmed query")
        void arguments() throws Exception {
            rankerReturns("pastor", List.of(pastorId));

            listIds("?q= pastor ");

            verify(ranker).rank(argThat(o -> o.getId().equals(orgId)), eq("pastor"));
        }

        @Test
        @DisplayName("the CSV export follows the ranking too")
        void export() throws Exception {
            rankerReturns("x", List.of(estrellaId, pastorId));

            MvcResult r = mockMvc.perform(get("/organizations/" + orgSlug + "/pieces/export?format=csv&q=x")
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isOk())
                    .andReturn();
            String csv = r.getResponse().getContentAsString().replace("﻿", "");
            int estrella = csv.indexOf("Estrella");
            int pastor = csv.indexOf("Pastor");
            assertThat(estrella).isPositive();
            assertThat(pastor).isGreaterThan(estrella);
            assertThat(csv).doesNotContain("Ovella");
        }
    }

    @Nested
    @DisplayName("when the ranker cannot answer")
    class Fallback {

        @Test
        @DisplayName("the LIKE filter on name and description applies")
        void like() throws Exception {
            when(ranker.rank(any(), any())).thenReturn(Optional.empty());

            assertThat(listIds("?q=pastor")).containsExactly(pastorId);
            assertThat(listIds("?q=ll")).containsExactlyInAnyOrder(ovellaId, estrellaId);
        }

        @Test
        @DisplayName("the ranker is not asked without a text filter")
        void noQuery() throws Exception {
            assertThat(listIds("")).hasSize(3);
            assertThat(listIds("?q=  ")).hasSize(3);

            verify(ranker, never()).rank(any(), any());
        }
    }
}
