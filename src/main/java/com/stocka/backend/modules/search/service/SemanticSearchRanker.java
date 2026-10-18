package com.stocka.backend.modules.search.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.pieces.service.PieceSearchRanker;
import com.stocka.backend.modules.search.SemanticSearchProperties;
import com.stocka.backend.modules.search.client.XeyeClient;
import com.stocka.backend.modules.search.client.XeyeClient.Hit;
import com.stocka.backend.modules.search.client.XeyeException;
import com.stocka.backend.modules.search.entity.SearchList;
import com.stocka.backend.modules.search.repository.SearchEntryRepository;
import com.stocka.backend.modules.search.repository.SearchListRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Answers piece listings with the XEYE ranking.
 *
 * <p>Returns {@link Optional#empty()} (so {@code PieceService} falls back to {@code LIKE}) when the
 * feature is off, the organization has nothing indexed yet, the query is blank or XEYE fails.
 * Hits under {@link SemanticSearchProperties#getMinScore()} are dropped.
 */
@Component
public class SemanticSearchRanker implements PieceSearchRanker {

    private static final Logger log = LoggerFactory.getLogger(SemanticSearchRanker.class);
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final SemanticSearchProperties properties;
    private final XeyeClient client;
    private final SearchListRepository lists;
    private final SearchEntryRepository entries;

    public SemanticSearchRanker(SemanticSearchProperties properties, XeyeClient client,
                                SearchListRepository lists, SearchEntryRepository entries) {
        this.properties = properties;
        this.client = client;
        this.lists = lists;
        this.entries = entries;
    }

    @Override
    public Optional<List<Integer>> rank(Organization organization, String query) {
        if (!properties.isConfigured()) return Optional.empty();
        String term = normalizeTerm(query);
        if (term == null) return Optional.empty();
        SearchList list = lists.findById(organization.getId()).orElse(null);
        if (list == null || entries.countByOrganizationId(organization.getId()) == 0) return Optional.empty();

        List<Hit> hits;
        try {
            hits = client.search(list.getListName(), term, properties.getMaxResults());
        } catch (XeyeException ex) {
            log.warn("semantic_search_failed org={} status={} code={}: {}",
                    organization.getId(), ex.status(), ex.code(), ex.getMessage());
            return Optional.empty();
        }
        Set<Integer> ids = new LinkedHashSet<>();
        for (Hit hit : hits) {
            if (hit.score() < properties.getMinScore()) continue;
            Integer pieceId = pieceIdOf(hit.params());
            if (pieceId != null) ids.add(pieceId);
        }
        return Optional.of(List.copyOf(ids));
    }

    /**
     * Trims the query and caps it to the XEYE limit.
     *
     * @param query raw query
     * @return the term to send, or {@code null} when blank
     */
    static String normalizeTerm(String query) {
        if (query == null || query.isBlank()) return null;
        String term = query.trim();
        return term.length() <= SemanticSearchProperties.XEYE_MAX_TERM_LENGTH
                ? term : term.substring(0, SemanticSearchProperties.XEYE_MAX_TERM_LENGTH);
    }

    /**
     * Reads the piece id out of the params of a hit; XEYE returns them as the stored JSON value,
     * but a textual JSON is tolerated too.
     *
     * @param params the {@code params} node of the hit, possibly {@code null}
     * @return the piece id, or {@code null} when the params carry none
     */
    static Integer pieceIdOf(JsonNode params) {
        if (params == null || params.isNull() || params.isMissingNode()) return null;
        JsonNode node = params;
        if (params.isTextual()) {
            try {
                node = JSON.readTree(params.asText());
            } catch (RuntimeException ex) {
                return null;
            }
        }
        JsonNode id = node.path("pieceId");
        if (id.isIntegralNumber()) return id.asInt();
        if (id.isTextual() && id.asText().matches("\\d{1,9}")) return Integer.parseInt(id.asText());
        return null;
    }
}
