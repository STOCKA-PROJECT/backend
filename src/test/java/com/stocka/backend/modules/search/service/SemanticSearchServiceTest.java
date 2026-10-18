package com.stocka.backend.modules.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

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

@ExtendWith(MockitoExtension.class)
@DisplayName("SemanticSearchService")
class SemanticSearchServiceTest {

    private static final LocalDateTime LAUNCHED = LocalDateTime.of(2026, 10, 18, 9, 30);

    @Mock private SemanticSearchIndexer indexer;
    @Mock private SearchListRepository lists;
    @Mock private SearchEntryRepository entries;
    @Mock private PieceRepository pieces;
    @Mock private OrganizationRepository organizations;

    private final SemanticSearchProperties properties = new SemanticSearchProperties();
    private final Organization org = new Organization().setId(3).setName("Acme");
    private SemanticSearchService sut;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        properties.setApiKey("k");
        sut = new SemanticSearchService(properties, indexer, lists, entries, pieces, organizations);
        lenient().when(pieces.countByOrganization(org)).thenReturn(4L);
        lenient().when(entries.countByOrganizationId(3)).thenReturn(2L);
    }

    @Nested
    @DisplayName("status")
    class Status {

        @Test
        @DisplayName("reports an organization that was never indexed")
        void neverIndexed() {
            when(lists.findById(3)).thenReturn(Optional.empty());

            SearchStatusDto status = sut.status(org);

            assertThat(status).isEqualTo(new SearchStatusDto(3, true, false, 2L, 4L, false, null));
        }

        @Test
        @DisplayName("reports the pending flag and the last launch of an indexed organization")
        void indexed() {
            when(lists.findById(3)).thenReturn(Optional.of(new SearchList().setOrganizationId(3)
                    .setPendingSince(LocalDateTime.now()).setLastTrainingLaunchedAt(LAUNCHED)));

            SearchStatusDto status = sut.status(org);

            assertThat(status.indexed()).isTrue();
            assertThat(status.trainingPending()).isTrue();
            assertThat(status.lastTrainingLaunchedAt()).isEqualTo(LAUNCHED);
        }

        @Test
        @DisplayName("reports enabled=false when the server has no XEYE key")
        void disabled() {
            properties.setApiKey("");
            when(lists.findById(3)).thenReturn(Optional.empty());

            assertThat(sut.status(org).enabled()).isFalse();
        }
    }

    @Nested
    @DisplayName("reindex")
    class Reindex {

        @Test
        @DisplayName("rejects with 409 search.not_configured when the feature is off")
        void notConfigured() {
            properties.setEnabled(false);

            assertThatThrownBy(() -> sut.reindex(org))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> {
                        assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(((ApiException) ex).getCode()).isEqualTo(ErrorCodes.SEARCH_NOT_CONFIGURED);
                    });
            verify(indexer, never()).reindex(3);
        }

        @Test
        @DisplayName("maps a XEYE failure to 503 search.unavailable")
        void unavailable() {
            doThrow(new XeyeException(503, "SERVICE_NOT_READY", "starting")).when(indexer).reindex(3);

            assertThatThrownBy(() -> sut.reindex(org))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> {
                        assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                        assertThat(((ApiException) ex).getCode()).isEqualTo(ErrorCodes.SEARCH_UNAVAILABLE);
                    });
        }

        @Test
        @DisplayName("rebuilds and returns the fresh status")
        void rebuilds() {
            when(lists.findById(3)).thenReturn(Optional.of(new SearchList().setOrganizationId(3)
                    .setPendingSince(LocalDateTime.now())));

            SearchStatusDto status = sut.reindex(org);

            verify(indexer).reindex(3);
            assertThat(status.indexed()).isTrue();
            assertThat(status.trainingPending()).isTrue();
        }
    }

    @Nested
    @DisplayName("reindexAll")
    class ReindexAll {

        @Test
        @DisplayName("rebuilds every organization in repository order")
        void all() {
            Organization other = new Organization().setId(4).setName("Beta");
            when(organizations.findAll()).thenReturn(List.of(org, other));
            when(lists.findById(3)).thenReturn(Optional.empty());
            when(lists.findById(4)).thenReturn(Optional.empty());

            List<SearchStatusDto> statuses = sut.reindexAll();

            verify(indexer).reindex(3);
            verify(indexer).reindex(4);
            assertThat(statuses).extracting(SearchStatusDto::organizationId).containsExactly(3, 4);
        }

        @Test
        @DisplayName("stops at the first organization XEYE rejects")
        void stopsOnFailure() {
            Organization other = new Organization().setId(4).setName("Beta");
            when(organizations.findAll()).thenReturn(List.of(org, other));
            doThrow(new XeyeException(0, null, "unreachable")).when(indexer).reindex(3);

            assertThatThrownBy(() -> sut.reindexAll()).isInstanceOf(ApiException.class);
            verify(indexer, never()).reindex(4);
        }
    }
}
