package com.stocka.backend.modules.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.repository.OrganizationRepository;
import com.stocka.backend.modules.search.SemanticSearchProperties;
import com.stocka.backend.modules.search.client.XeyeException;
import com.stocka.backend.modules.search.entity.SearchList;
import com.stocka.backend.modules.search.repository.SearchListRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("SemanticSearchBootstrap")
class SemanticSearchBootstrapTest {

    @Mock private SemanticSearchIndexer indexer;
    @Mock private SearchListRepository lists;
    @Mock private OrganizationRepository organizations;

    private final SemanticSearchProperties properties = new SemanticSearchProperties();
    private SemanticSearchBootstrap sut;

    private final Organization acme = new Organization().setId(1).setName("Acme");
    private final Organization beta = new Organization().setId(2).setName("Beta");

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        properties.setApiKey("k");
        sut = new SemanticSearchBootstrap(properties, indexer, lists, organizations);
    }

    @Nested
    @DisplayName("on application ready")
    class OnReady {

        @Test
        @DisplayName("does nothing when the feature is not configured")
        void notConfigured() {
            properties.setEnabled(false);
            sut.onApplicationReady();
            verifyNoInteractions(indexer, lists, organizations);
        }

        @Test
        @DisplayName("backfills when configured")
        void configured() {
            when(lists.findAll()).thenReturn(List.of());
            when(organizations.findAll()).thenReturn(List.of(acme));
            sut.onApplicationReady();
            verify(indexer).reindex(1);
        }
    }

    @Nested
    @DisplayName("backfillMissing")
    class BackfillMissing {

        @Test
        @DisplayName("indexes only the organizations without a list")
        void onlyMissing() {
            when(lists.findAll()).thenReturn(List.of(new SearchList().setOrganizationId(1)));
            when(organizations.findAll()).thenReturn(List.of(acme, beta));

            int done = sut.backfillMissing();

            assertThat(done).isEqualTo(1);
            verify(indexer, never()).reindex(1);
            verify(indexer).reindex(2);
        }

        @Test
        @DisplayName("does nothing when every organization is indexed")
        void nothingMissing() {
            when(lists.findAll()).thenReturn(List.of(new SearchList().setOrganizationId(1)));
            when(organizations.findAll()).thenReturn(List.of(acme));

            assertThat(sut.backfillMissing()).isZero();
            verifyNoInteractions(indexer);
        }

        @Test
        @DisplayName("keeps going after one organization fails")
        void continuesAfterFailure() {
            when(lists.findAll()).thenReturn(List.of());
            when(organizations.findAll()).thenReturn(List.of(acme, beta));
            doThrow(new XeyeException(503, "SERVICE_UNAVAILABLE", "down")).when(indexer).reindex(1);

            int done = sut.backfillMissing();

            assertThat(done).isEqualTo(1);
            verify(indexer).reindex(2);
        }
    }
}
