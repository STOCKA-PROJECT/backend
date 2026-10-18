package com.stocka.backend.modules.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.search.SemanticSearchProperties;
import com.stocka.backend.modules.search.client.XeyeClient;
import com.stocka.backend.modules.search.client.XeyeClient.Hit;
import com.stocka.backend.modules.search.client.XeyeException;
import com.stocka.backend.modules.search.entity.SearchList;
import com.stocka.backend.modules.search.repository.SearchEntryRepository;
import com.stocka.backend.modules.search.repository.SearchListRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("SemanticSearchRanker")
class SemanticSearchRankerTest {

    private static final Integer ORG_ID = 3;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Mock private XeyeClient client;
    @Mock private SearchListRepository lists;
    @Mock private SearchEntryRepository entries;

    private final SemanticSearchProperties properties = new SemanticSearchProperties();
    private final Organization org = new Organization().setId(ORG_ID);
    private SemanticSearchRanker sut;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        properties.setApiKey("k");
        properties.setMaxResults(50);
        properties.setMinScore(0.3);
        sut = new SemanticSearchRanker(properties, client, lists, entries);
    }

    private void indexed() {
        lenient().when(lists.findById(ORG_ID)).thenReturn(Optional.of(
                new SearchList().setOrganizationId(ORG_ID).setXeyeListId(9L).setListName("stocka-org-3")));
        lenient().when(entries.countByOrganizationId(ORG_ID)).thenReturn(5L);
    }

    private static Hit hit(double score, String params) {
        JsonNode node = params == null ? JSON.nullNode() : JSON.readTree(params);
        return new Hit("item", score, node);
    }

    @Nested
    @DisplayName("falls back (empty) when")
    class Fallback {

        @Test
        @DisplayName("the feature is not configured")
        void notConfigured() {
            properties.setApiKey("");
            assertThat(sut.rank(org, "pastor")).isEmpty();
            verifyNoInteractions(client, lists);
        }

        @Test
        @DisplayName("the query is blank")
        void blankQuery() {
            indexed();
            assertThat(sut.rank(org, "   ")).isEmpty();
            assertThat(sut.rank(org, null)).isEmpty();
            verifyNoInteractions(client);
        }

        @Test
        @DisplayName("the organization has no list yet")
        void noList() {
            when(lists.findById(ORG_ID)).thenReturn(Optional.empty());
            assertThat(sut.rank(org, "pastor")).isEmpty();
            verifyNoInteractions(client);
        }

        @Test
        @DisplayName("the organization has a list but nothing indexed")
        void nothingIndexed() {
            indexed();
            when(entries.countByOrganizationId(ORG_ID)).thenReturn(0L);
            assertThat(sut.rank(org, "pastor")).isEmpty();
            verifyNoInteractions(client);
        }

        @Test
        @DisplayName("XEYE fails, without propagating the exception")
        void xeyeFails() {
            indexed();
            when(client.search(anyString(), anyString(), anyInt()))
                    .thenThrow(new XeyeException(503, "SERVICE_NOT_READY", "starting"));
            assertThat(sut.rank(org, "pastor")).isEmpty();
        }
    }

    @Nested
    @DisplayName("ranking")
    class Ranking {

        @BeforeEach
        void ready() {
            indexed();
        }

        @Test
        @DisplayName("returns the piece ids in XEYE order")
        void order() {
            when(client.search("stocka-org-3", "pastor", 50)).thenReturn(List.of(
                    hit(0.9, "{\"pieceId\":42}"), hit(0.8, "{\"pieceId\":7}"), hit(0.5, "{\"pieceId\":13}")));

            assertThat(sut.rank(org, "pastor")).contains(List.of(42, 7, 13));
        }

        @Test
        @DisplayName("drops hits under the minimum score")
        void minScore() {
            when(client.search(anyString(), anyString(), anyInt())).thenReturn(List.of(
                    hit(0.9, "{\"pieceId\":42}"), hit(0.29, "{\"pieceId\":7}")));

            assertThat(sut.rank(org, "pastor")).contains(List.of(42));
        }

        @Test
        @DisplayName("keeps a hit exactly at the minimum score")
        void minScoreInclusive() {
            when(client.search(anyString(), anyString(), anyInt())).thenReturn(List.of(hit(0.3, "{\"pieceId\":42}")));

            assertThat(sut.rank(org, "pastor")).contains(List.of(42));
        }

        @Test
        @DisplayName("returns an empty ranking (not a fallback) when nothing matches")
        void noMatches() {
            when(client.search(anyString(), anyString(), anyInt())).thenReturn(List.of());

            assertThat(sut.rank(org, "pastor")).contains(List.of());
        }

        @Test
        @DisplayName("de-duplicates piece ids keeping the best position")
        void deduplicates() {
            when(client.search(anyString(), anyString(), anyInt())).thenReturn(List.of(
                    hit(0.9, "{\"pieceId\":42}"), hit(0.8, "{\"pieceId\":7}"), hit(0.7, "{\"pieceId\":42}")));

            assertThat(sut.rank(org, "pastor")).contains(List.of(42, 7));
        }

        @Test
        @DisplayName("reads params returned as a JSON string")
        void textualParams() {
            when(client.search(anyString(), anyString(), anyInt())).thenReturn(List.of(
                    hit(0.9, "\"{\\\"pieceId\\\":42}\"")));

            assertThat(sut.rank(org, "pastor")).contains(List.of(42));
        }

        @Test
        @DisplayName("ignores hits without a usable piece id")
        void unusableParams() {
            when(client.search(anyString(), anyString(), anyInt())).thenReturn(List.of(
                    hit(0.9, null), hit(0.9, "{\"sku\":\"x\"}"), hit(0.9, "{\"pieceId\":\"abc\"}"),
                    hit(0.9, "\"not json\""), hit(0.8, "{\"pieceId\":\"7\"}")));

            assertThat(sut.rank(org, "pastor")).contains(List.of(7));
        }

        @Test
        @DisplayName("trims the query and caps it to the XEYE term length")
        void normalizesTerm() {
            when(client.search(anyString(), anyString(), anyInt())).thenReturn(List.of());

            sut.rank(org, "  " + "a".repeat(600) + "  ");

            verify(client).search("stocka-org-3", "a".repeat(SemanticSearchProperties.XEYE_MAX_TERM_LENGTH), 50);
        }

        @Test
        @DisplayName("asks XEYE for the configured number of results")
        void maxResults() {
            properties.setMaxResults(7);
            when(client.search(anyString(), anyString(), anyInt())).thenReturn(List.of());

            sut.rank(org, "pastor");

            verify(client).search("stocka-org-3", "pastor", 7);
        }
    }

    @Nested
    @DisplayName("helpers")
    class Helpers {

        @Test
        @DisplayName("normalizeTerm")
        void normalizeTerm() {
            assertThat(SemanticSearchRanker.normalizeTerm(null)).isNull();
            assertThat(SemanticSearchRanker.normalizeTerm(" ")).isNull();
            assertThat(SemanticSearchRanker.normalizeTerm(" ovella ")).isEqualTo("ovella");
            assertThat(SemanticSearchRanker.normalizeTerm("x".repeat(501))).hasSize(500);
        }

        @Test
        @DisplayName("pieceIdOf")
        void pieceIdOf() {
            assertThat(SemanticSearchRanker.pieceIdOf(null)).isNull();
            assertThat(SemanticSearchRanker.pieceIdOf(JSON.nullNode())).isNull();
            assertThat(SemanticSearchRanker.pieceIdOf(JSON.missingNode())).isNull();
            assertThat(SemanticSearchRanker.pieceIdOf(JSON.readTree("{\"pieceId\":42}"))).isEqualTo(42);
            assertThat(SemanticSearchRanker.pieceIdOf(JSON.readTree("{\"pieceId\":\"42\"}"))).isEqualTo(42);
            assertThat(SemanticSearchRanker.pieceIdOf(JSON.readTree("{\"pieceId\":4.2}"))).isNull();
            assertThat(SemanticSearchRanker.pieceIdOf(JSON.readTree("{\"pieceId\":\"9999999999\"}"))).isNull();
            assertThat(SemanticSearchRanker.pieceIdOf(JSON.readTree("\"{\\\"pieceId\\\":3}\""))).isEqualTo(3);
            assertThat(SemanticSearchRanker.pieceIdOf(JSON.readTree("\"{broken\""))).isNull();
            assertThat(SemanticSearchRanker.pieceIdOf(JSON.readTree("[1,2]"))).isNull();
        }
    }
}
