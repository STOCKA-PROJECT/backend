package com.stocka.backend.modules.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.service.OrganizationService;
import com.stocka.backend.modules.pieces.entity.Piece;
import com.stocka.backend.modules.pieces.repository.PieceRepository;
import com.stocka.backend.modules.pieces.service.PieceService;
import com.stocka.backend.modules.search.SemanticSearchProperties;
import com.stocka.backend.modules.search.client.XeyeClient;
import com.stocka.backend.modules.search.client.XeyeClient.ElementInput;
import com.stocka.backend.modules.search.client.XeyeClient.XeyeList;
import com.stocka.backend.modules.search.client.XeyeException;
import com.stocka.backend.modules.search.entity.SearchEntry;
import com.stocka.backend.modules.search.entity.SearchList;
import com.stocka.backend.modules.search.repository.SearchEntryRepository;
import com.stocka.backend.modules.search.repository.SearchListRepository;
import com.stocka.backend.modules.search.service.SemanticSearchIndexer.ReindexResult;

@ExtendWith(MockitoExtension.class)
@DisplayName("SemanticSearchIndexer")
class SemanticSearchIndexerTest {

    private static final Integer ORG_ID = 3;
    private static final Integer PIECE_ID = 42;
    private static final long LIST_ID = 9L;
    private static final long ELEMENT_ID = 501L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 18, 12, 0);
    private static final LocalDateTime EARLIER = NOW.minusHours(2);

    @Mock private XeyeClient client;
    @Mock private SearchListRepository lists;
    @Mock private SearchEntryRepository entries;
    @Mock private PieceRepository pieces;
    @Mock private PieceService pieceService;
    @Mock private OrganizationService organizationService;

    private final SemanticSearchProperties properties = new SemanticSearchProperties();
    private SemanticSearchIndexer sut;
    private Organization org;
    private Piece piece;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        properties.setApiKey("k");
        Clock clock = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        sut = new SemanticSearchIndexer(properties, client, lists, entries, pieces, pieceService, organizationService, clock);
        org = new Organization().setId(ORG_ID).setName("Acme");
        piece = new Piece().setId(PIECE_ID).setName("Pastor").setOrganization(org);
        lenient().when(lists.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(entries.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(entries.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(pieceService.valuesOf(any())).thenReturn(List.of());
        lenient().when(pieceService.orgValuesOf(any())).thenReturn(List.of());
    }

    private SearchList knownList() {
        return new SearchList().setOrganizationId(ORG_ID).setXeyeListId(LIST_ID).setListName("stocka-org-3");
    }

    private SearchEntry knownEntry() {
        return new SearchEntry().setPieceId(PIECE_ID).setOrganizationId(ORG_ID)
                .setXeyeElementId(ELEMENT_ID).setIndexedAt(EARLIER);
    }

    private static Instant instant(LocalDateTime at) {
        return at.toInstant(ZoneOffset.UTC);
    }

    @Nested
    @DisplayName("when the feature is not configured")
    class NotConfigured {

        @BeforeEach
        void disable() {
            properties.setEnabled(false);
        }

        @Test
        @DisplayName("index does nothing")
        void index() {
            sut.index(PIECE_ID);
            verifyNoInteractions(client, lists, entries, pieces);
        }

        @Test
        @DisplayName("remove does nothing")
        void remove() {
            sut.remove(PIECE_ID);
            verifyNoInteractions(client, lists, entries);
        }
    }

    @Nested
    @DisplayName("index")
    class Index {

        @BeforeEach
        void pieceExists() {
            lenient().when(pieces.findById(PIECE_ID)).thenReturn(Optional.of(piece));
        }

        @Test
        @DisplayName("creates the XEYE list on first use and remembers it")
        void createsList() {
            when(lists.findById(ORG_ID)).thenReturn(Optional.empty());
            when(client.findList("stocka-org-3")).thenReturn(Optional.empty());
            when(client.createList("stocka-org-3", "Stocka · Acme")).thenReturn(new XeyeList(LIST_ID, "stocka-org-3"));
            when(entries.findById(PIECE_ID)).thenReturn(Optional.empty());
            when(client.createElement(eq(LIST_ID), any())).thenReturn(ELEMENT_ID);

            sut.index(PIECE_ID);

            ArgumentCaptor<SearchList> saved = ArgumentCaptor.forClass(SearchList.class);
            verify(lists, atLeastOnce()).save(saved.capture());
            SearchList list = saved.getAllValues().get(0);
            assertThat(list.getOrganizationId()).isEqualTo(ORG_ID);
            assertThat(list.getXeyeListId()).isEqualTo(LIST_ID);
            assertThat(list.getListName()).isEqualTo("stocka-org-3");
        }

        @Test
        @DisplayName("re-links a list that already exists in XEYE instead of creating a duplicate")
        void relinksRemoteList() {
            when(lists.findById(ORG_ID)).thenReturn(Optional.empty());
            when(client.findList("stocka-org-3")).thenReturn(Optional.of(new XeyeList(LIST_ID, "stocka-org-3")));
            when(entries.findById(PIECE_ID)).thenReturn(Optional.empty());
            when(client.createElement(eq(LIST_ID), any())).thenReturn(ELEMENT_ID);

            sut.index(PIECE_ID);

            verify(client, never()).createList(any(), any());
        }

        @Test
        @DisplayName("creates the element and the entry for a piece indexed for the first time")
        void createsElement() {
            when(lists.findById(ORG_ID)).thenReturn(Optional.of(knownList()));
            when(entries.findById(PIECE_ID)).thenReturn(Optional.empty());
            when(client.createElement(eq(LIST_ID), any())).thenReturn(ELEMENT_ID);

            sut.index(PIECE_ID);

            ArgumentCaptor<ElementInput> input = ArgumentCaptor.forClass(ElementInput.class);
            verify(client).createElement(eq(LIST_ID), input.capture());
            assertThat(input.getValue().text()).isEqualTo("Pastor");
            assertThat(input.getValue().params()).containsEntry("pieceId", PIECE_ID);
            ArgumentCaptor<SearchEntry> entry = ArgumentCaptor.forClass(SearchEntry.class);
            verify(entries).save(entry.capture());
            assertThat(entry.getValue().getPieceId()).isEqualTo(PIECE_ID);
            assertThat(entry.getValue().getOrganizationId()).isEqualTo(ORG_ID);
            assertThat(entry.getValue().getXeyeElementId()).isEqualTo(ELEMENT_ID);
            assertThat(entry.getValue().getIndexedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("updates the element of a piece that is already indexed")
        void updatesElement() {
            when(lists.findById(ORG_ID)).thenReturn(Optional.of(knownList()));
            SearchEntry entry = knownEntry();
            when(entries.findById(PIECE_ID)).thenReturn(Optional.of(entry));

            sut.index(PIECE_ID);

            verify(client).updateElement(eq(LIST_ID), eq(ELEMENT_ID), any());
            verify(client, never()).createElement(anyLong(), any());
            assertThat(entry.getIndexedAt()).isEqualTo(NOW);
            verify(entries).save(entry);
        }

        @Test
        @DisplayName("recreates the element when XEYE no longer has it")
        void recreatesMissingElement() {
            when(lists.findById(ORG_ID)).thenReturn(Optional.of(knownList()));
            when(entries.findById(PIECE_ID)).thenReturn(Optional.of(knownEntry()));
            doThrow(new XeyeException(404, "NOT_FOUND", "gone"))
                    .when(client).updateElement(eq(LIST_ID), eq(ELEMENT_ID), any());
            when(client.createElement(eq(LIST_ID), any())).thenReturn(777L);

            sut.index(PIECE_ID);

            ArgumentCaptor<SearchEntry> entry = ArgumentCaptor.forClass(SearchEntry.class);
            verify(entries).save(entry.capture());
            assertThat(entry.getValue().getXeyeElementId()).isEqualTo(777L);
        }

        @Test
        @DisplayName("propagates other XEYE failures")
        void propagatesFailures() {
            when(lists.findById(ORG_ID)).thenReturn(Optional.of(knownList()));
            when(entries.findById(PIECE_ID)).thenReturn(Optional.of(knownEntry()));
            doThrow(new XeyeException(503, "SERVICE_NOT_READY", "starting"))
                    .when(client).updateElement(eq(LIST_ID), eq(ELEMENT_ID), any());

            assertThatThrownBy(() -> sut.index(PIECE_ID)).isInstanceOf(XeyeException.class);
            verify(entries, never()).save(any());
        }

        @Test
        @DisplayName("keeps the first pendingSince but bumps lastChangeAt on a later change")
        void marksPendingOnce() {
            SearchList list = knownList().setPendingSince(EARLIER).setLastChangeAt(EARLIER);
            when(lists.findById(ORG_ID)).thenReturn(Optional.of(list));
            when(entries.findById(PIECE_ID)).thenReturn(Optional.of(knownEntry()));

            sut.index(PIECE_ID);

            assertThat(list.getPendingSince()).isEqualTo(EARLIER);
            assertThat(list.getLastChangeAt()).isEqualTo(NOW);
            verify(lists).save(list);
        }

        @Test
        @DisplayName("sets pendingSince and lastChangeAt to now on the first change")
        void setsPending() {
            SearchList list = knownList();
            when(lists.findById(ORG_ID)).thenReturn(Optional.of(list));
            when(entries.findById(PIECE_ID)).thenReturn(Optional.of(knownEntry()));

            sut.index(PIECE_ID);

            assertThat(list.getPendingSince()).isEqualTo(NOW);
            assertThat(list.getLastChangeAt()).isEqualTo(NOW);
            verify(lists).save(list);
        }

        @Test
        @DisplayName("removes the element when the piece was deleted in the meantime")
        void deletedMeanwhile() {
            when(pieces.findById(PIECE_ID)).thenReturn(Optional.empty());
            SearchEntry entry = knownEntry();
            when(entries.findById(PIECE_ID)).thenReturn(Optional.of(entry));
            when(lists.findById(ORG_ID)).thenReturn(Optional.of(knownList()));

            sut.index(PIECE_ID);

            verify(client).deleteElement(LIST_ID, ELEMENT_ID);
            verify(entries).delete(entry);
        }
    }

    @Nested
    @DisplayName("remove")
    class Remove {

        @Test
        @DisplayName("does nothing for a piece that was never indexed")
        void neverIndexed() {
            when(entries.findById(PIECE_ID)).thenReturn(Optional.empty());

            sut.remove(PIECE_ID);

            verifyNoInteractions(client);
            verify(entries, never()).delete(any());
        }

        @Test
        @DisplayName("deletes the element, drops the entry and marks the list pending")
        void deletes() {
            SearchEntry entry = knownEntry();
            SearchList list = knownList();
            when(entries.findById(PIECE_ID)).thenReturn(Optional.of(entry));
            when(lists.findById(ORG_ID)).thenReturn(Optional.of(list));

            sut.remove(PIECE_ID);

            verify(client).deleteElement(LIST_ID, ELEMENT_ID);
            verify(entries).delete(entry);
            assertThat(list.getPendingSince()).isEqualTo(NOW);
            assertThat(list.getLastChangeAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("only drops the entry when the list is unknown")
        void unknownList() {
            SearchEntry entry = knownEntry();
            when(entries.findById(PIECE_ID)).thenReturn(Optional.of(entry));
            when(lists.findById(ORG_ID)).thenReturn(Optional.empty());

            sut.remove(PIECE_ID);

            verifyNoInteractions(client);
            verify(entries).delete(entry);
        }
    }

    @Nested
    @DisplayName("reindex")
    class Reindex {

        private final Piece other = new Piece().setId(43).setName("Ovella").setOrganization(org);

        @BeforeEach
        void orgKnown() {
            when(organizationService.findById(ORG_ID)).thenReturn(org);
            lenient().when(lists.findById(ORG_ID)).thenReturn(Optional.of(knownList()));
        }

        @Test
        @DisplayName("deletes the stale elements and recreates every active piece in one batch")
        void rebuilds() {
            SearchEntry kept = knownEntry();
            SearchEntry stale = new SearchEntry().setPieceId(99).setOrganizationId(ORG_ID).setXeyeElementId(900L);
            when(entries.findByOrganizationId(ORG_ID)).thenReturn(List.of(kept, stale));
            when(pieces.findByOrganization(org)).thenReturn(List.of(piece, other));
            when(client.createElements(eq(LIST_ID), anyList())).thenReturn(List.of(1001L, 1002L));

            ReindexResult result = sut.reindex(ORG_ID);

            assertThat(result).isEqualTo(new ReindexResult(2, 1));
            verify(client).deleteElements(LIST_ID, List.of(ELEMENT_ID, 900L));
            ArgumentCaptor<List<ElementInput>> inputs = ArgumentCaptor.forClass(List.class);
            verify(client).createElements(eq(LIST_ID), inputs.capture());
            assertThat(inputs.getValue()).extracting(ElementInput::text).containsExactly("Pastor", "Ovella");
            assertThat(kept.getXeyeElementId()).isEqualTo(1001L);
            assertThat(kept.getIndexedAt()).isEqualTo(NOW);
            verify(entries).deleteAll(List.of(stale));
            ArgumentCaptor<List<SearchEntry>> saved = ArgumentCaptor.forClass(List.class);
            verify(entries).saveAll(saved.capture());
            assertThat(saved.getValue()).extracting(SearchEntry::getPieceId).containsExactly(PIECE_ID, 43);
            assertThat(saved.getValue().get(1).getXeyeElementId()).isEqualTo(1002L);
            assertThat(saved.getValue().get(1).getOrganizationId()).isEqualTo(ORG_ID);
        }

        @Test
        @DisplayName("skips the delete call when nothing was indexed before")
        void firstBackfill() {
            when(entries.findByOrganizationId(ORG_ID)).thenReturn(List.of());
            when(pieces.findByOrganization(org)).thenReturn(List.of(piece));
            when(client.createElements(eq(LIST_ID), anyList())).thenReturn(List.of(1001L));

            ReindexResult result = sut.reindex(ORG_ID);

            assertThat(result).isEqualTo(new ReindexResult(1, 0));
            verify(client, never()).deleteElements(anyLong(), anyList());
        }

        @Test
        @DisplayName("marks the list pending after a rebuild")
        void marksPending() {
            SearchList list = knownList();
            when(lists.findById(ORG_ID)).thenReturn(Optional.of(list));
            when(entries.findByOrganizationId(ORG_ID)).thenReturn(List.of());
            when(pieces.findByOrganization(org)).thenReturn(List.of(piece));
            when(client.createElements(eq(LIST_ID), anyList())).thenReturn(List.of(1001L));

            sut.reindex(ORG_ID);

            assertThat(list.getPendingSince()).isEqualTo(NOW);
            assertThat(list.getLastChangeAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("does not mark pending an organization with nothing to index and nothing stale")
        void nothingToDo() {
            SearchList list = knownList();
            when(lists.findById(ORG_ID)).thenReturn(Optional.of(list));
            when(entries.findByOrganizationId(ORG_ID)).thenReturn(List.of());
            when(pieces.findByOrganization(org)).thenReturn(List.of());
            when(client.createElements(eq(LIST_ID), anyList())).thenReturn(List.of());

            ReindexResult result = sut.reindex(ORG_ID);

            assertThat(result).isEqualTo(new ReindexResult(0, 0));
            assertThat(list.getPendingSince()).isNull();
            assertThat(list.getLastChangeAt()).isNull();
        }

        @Test
        @DisplayName("fails loudly when XEYE returns a different number of ids")
        void idCountMismatch() {
            when(entries.findByOrganizationId(ORG_ID)).thenReturn(List.of());
            when(pieces.findByOrganization(org)).thenReturn(List.of(piece, other));
            when(client.createElements(eq(LIST_ID), anyList())).thenReturn(List.of(1001L));

            assertThatThrownBy(() -> sut.reindex(ORG_ID))
                    .isInstanceOf(XeyeException.class)
                    .hasMessageContaining("1 ids for 2 elements");
            verify(entries, never()).saveAll(any());
        }

        @Test
        @DisplayName("creates the list first when the organization was never indexed")
        void createsListFirst() {
            when(lists.findById(ORG_ID)).thenReturn(Optional.empty());
            when(client.findList("stocka-org-3")).thenReturn(Optional.empty());
            when(client.createList("stocka-org-3", "Stocka · Acme")).thenReturn(new XeyeList(LIST_ID, "stocka-org-3"));
            when(entries.findByOrganizationId(ORG_ID)).thenReturn(List.of());
            when(pieces.findByOrganization(org)).thenReturn(List.of());
            when(client.createElements(eq(LIST_ID), anyList())).thenReturn(List.of());

            sut.reindex(ORG_ID);

            verify(client).createList("stocka-org-3", "Stocka · Acme");
        }
    }

    @Test
    @DisplayName("uses the injected clock for timestamps")
    void clock() {
        assertThat(instant(NOW)).isEqualTo(Instant.parse("2026-10-18T12:00:00Z"));
    }
}
