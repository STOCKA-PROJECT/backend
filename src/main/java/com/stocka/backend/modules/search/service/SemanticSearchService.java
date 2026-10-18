package com.stocka.backend.modules.search.service;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.stocka.backend.modules.common.error.ApiException;
import com.stocka.backend.modules.common.error.ErrorCodes;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.repository.OrganizationRepository;
import com.stocka.backend.modules.pieces.repository.PieceRepository;
import com.stocka.backend.modules.search.SemanticSearchProperties;
import com.stocka.backend.modules.search.client.XeyeException;
import com.stocka.backend.modules.search.dto.SearchStatusDto;
import com.stocka.backend.modules.search.entity.SearchList;
import com.stocka.backend.modules.search.repository.SearchEntryRepository;
import com.stocka.backend.modules.search.repository.SearchListRepository;

/** Status and maintenance of the semantic index, as exposed to the API. */
@Service
public class SemanticSearchService {

    private static final Logger log = LoggerFactory.getLogger(SemanticSearchService.class);

    private final SemanticSearchProperties properties;
    private final SemanticSearchIndexer indexer;
    private final SearchListRepository lists;
    private final SearchEntryRepository entries;
    private final PieceRepository pieces;
    private final OrganizationRepository organizations;

    public SemanticSearchService(SemanticSearchProperties properties, SemanticSearchIndexer indexer,
                                 SearchListRepository lists, SearchEntryRepository entries,
                                 PieceRepository pieces, OrganizationRepository organizations) {
        this.properties = properties;
        this.indexer = indexer;
        this.lists = lists;
        this.entries = entries;
        this.pieces = pieces;
        this.organizations = organizations;
    }

    /**
     * Current state of the index of an organization.
     *
     * @param org the organization
     * @return the status
     */
    public SearchStatusDto status(Organization org) {
        SearchList list = lists.findById(org.getId()).orElse(null);
        return new SearchStatusDto(
                org.getId(),
                properties.isConfigured(),
                list != null,
                entries.countByOrganizationId(org.getId()),
                pieces.countByOrganization(org),
                list != null && list.getPendingSince() != null,
                list == null ? null : list.getLastTrainingLaunchedAt());
    }

    /**
     * Rebuilds the index of an organization.
     *
     * @param org the organization
     * @return the status after the rebuild
     * @throws ApiException 409 ({@code search.not_configured}) when the feature is off, 503
     *                      ({@code search.unavailable}) when XEYE rejects the rebuild
     */
    public SearchStatusDto reindex(Organization org) {
        if (!properties.isConfigured()) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.SEARCH_NOT_CONFIGURED);
        }
        try {
            indexer.reindex(org.getId());
        } catch (XeyeException ex) {
            log.warn("semantic_reindex_failed org={} status={} code={}: {}",
                    org.getId(), ex.status(), ex.code(), ex.getMessage());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.SEARCH_UNAVAILABLE);
        }
        return status(org);
    }

    /**
     * Rebuilds the index of every active organization (global admin backfill). Stops at the first
     * organization XEYE rejects; the ones already rebuilt stay rebuilt.
     *
     * @return the status of each organization, in id order
     * @throws ApiException as {@link #reindex(Organization)}
     */
    public List<SearchStatusDto> reindexAll() {
        List<SearchStatusDto> statuses = new ArrayList<>();
        for (Organization org : organizations.findAll()) {
            statuses.add(reindex(org));
        }
        return statuses;
    }
}
