package com.stocka.backend.modules.search.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

/**
 * Keeps the XEYE list of each organization in sync with its pieces.
 *
 * <p>One element per active piece. {@link #index(Integer)} and {@link #remove(Integer)} are the
 * incremental path (one piece, one HTTP call); {@link #reindex(Integer)} rebuilds a whole
 * organization in two batch calls and is the way to backfill after enabling the feature or to
 * repair drift (renamed types or locations are not re-indexed until the piece is edited again).
 * Every change marks the list as pending and bumps its last change so {@link SemanticSearchTrainer}
 * launches one training once a burst of edits goes quiet. All methods are no-ops when the feature is not configured.
 */
@Service
public class SemanticSearchIndexer {

    private static final Logger log = LoggerFactory.getLogger(SemanticSearchIndexer.class);

    /** Outcome of a full rebuild: how many pieces are now indexed and how many links were stale. */
    public record ReindexResult(int indexed, int removed) {}

    private final SemanticSearchProperties properties;
    private final XeyeClient client;
    private final SearchListRepository lists;
    private final SearchEntryRepository entries;
    private final PieceRepository pieces;
    private final PieceService pieceService;
    private final OrganizationService organizationService;
    private final Clock clock;

    @Autowired
    public SemanticSearchIndexer(SemanticSearchProperties properties, XeyeClient client,
                                 SearchListRepository lists, SearchEntryRepository entries,
                                 PieceRepository pieces, PieceService pieceService,
                                 OrganizationService organizationService) {
        this(properties, client, lists, entries, pieces, pieceService, organizationService, Clock.systemDefaultZone());
    }

    SemanticSearchIndexer(SemanticSearchProperties properties, XeyeClient client,
                          SearchListRepository lists, SearchEntryRepository entries,
                          PieceRepository pieces, PieceService pieceService,
                          OrganizationService organizationService, Clock clock) {
        this.properties = properties;
        this.client = client;
        this.lists = lists;
        this.entries = entries;
        this.pieces = pieces;
        this.pieceService = pieceService;
        this.organizationService = organizationService;
        this.clock = clock;
    }

    /**
     * Creates or refreshes the element of a piece. A piece that can no longer be loaded (deleted
     * in the meantime) is removed instead.
     *
     * @param pieceId id of the piece
     * @throws XeyeException when XEYE rejects the change
     */
    @Transactional
    public void index(Integer pieceId) {
        if (!properties.isConfigured()) return;
        Optional<Piece> piece = pieces.findById(pieceId);
        if (piece.isEmpty()) {
            remove(pieceId);
            return;
        }
        indexPiece(piece.get());
    }

    /**
     * Deletes the element of a piece, if it was ever indexed.
     *
     * @param pieceId id of the piece (may already be soft-deleted)
     * @throws XeyeException when XEYE rejects the deletion
     */
    @Transactional
    public void remove(Integer pieceId) {
        if (!properties.isConfigured()) return;
        SearchEntry entry = entries.findById(pieceId).orElse(null);
        if (entry == null) return;
        SearchList list = lists.findById(entry.getOrganizationId()).orElse(null);
        if (list != null) {
            client.deleteElement(list.getXeyeListId(), entry.getXeyeElementId());
            markPending(list);
        }
        entries.delete(entry);
    }

    /**
     * Rebuilds the whole list of an organization: every stale element is deleted and every active
     * piece is (re)created in batches. Elements are recreated rather than patched one by one so a
     * large organization costs two requests instead of thousands.
     *
     * @param organizationId id of the organization
     * @return how many pieces were indexed and how many stale links were dropped
     * @throws XeyeException when XEYE rejects a batch
     */
    @Transactional
    public ReindexResult reindex(Integer organizationId) {
        Organization org = organizationService.findById(organizationId);
        SearchList list = ensureList(org);
        Map<Integer, SearchEntry> existing = new LinkedHashMap<>();
        for (SearchEntry entry : entries.findByOrganizationId(organizationId)) {
            existing.put(entry.getPieceId(), entry);
        }
        List<Piece> active = pieces.findByOrganization(org);

        List<Long> staleElementIds = existing.values().stream().map(SearchEntry::getXeyeElementId).toList();
        if (!staleElementIds.isEmpty()) {
            client.deleteElements(list.getXeyeListId(), staleElementIds);
        }
        List<Long> elementIds = client.createElements(list.getXeyeListId(),
                active.stream().map(this::toInput).toList());
        if (elementIds.size() != active.size()) {
            throw new XeyeException(0, null, "XEYE returned " + elementIds.size() + " ids for "
                    + active.size() + " elements");
        }

        LocalDateTime now = now();
        List<SearchEntry> toSave = new ArrayList<>(active.size());
        for (int i = 0; i < active.size(); i++) {
            Piece piece = active.get(i);
            SearchEntry entry = existing.remove(piece.getId());
            if (entry == null) {
                entry = new SearchEntry().setPieceId(piece.getId()).setOrganizationId(organizationId);
            }
            toSave.add(entry.setXeyeElementId(elementIds.get(i)).setIndexedAt(now));
        }
        entries.deleteAll(new ArrayList<>(existing.values()));
        entries.saveAll(toSave);
        if (!active.isEmpty() || !staleElementIds.isEmpty()) {
            markPending(list);
        }
        log.info("semantic_reindex org={} indexed={} removed={}", organizationId, active.size(), existing.size());
        return new ReindexResult(active.size(), existing.size());
    }

    private void indexPiece(Piece piece) {
        Organization org = piece.getOrganization();
        SearchList list = ensureList(org);
        ElementInput input = toInput(piece);
        SearchEntry entry = entries.findById(piece.getId()).orElse(null);
        if (entry != null) {
            try {
                client.updateElement(list.getXeyeListId(), entry.getXeyeElementId(), input);
                entries.save(entry.setIndexedAt(now()));
                markPending(list);
                return;
            } catch (XeyeException ex) {
                if (!ex.isNotFound()) throw ex;
                log.info("semantic_element_missing pieceId={} elementId={}: recreating",
                        piece.getId(), entry.getXeyeElementId());
            }
        }
        long elementId = client.createElement(list.getXeyeListId(), input);
        entries.save(new SearchEntry()
                .setPieceId(piece.getId())
                .setOrganizationId(org.getId())
                .setXeyeElementId(elementId)
                .setIndexedAt(now()));
        markPending(list);
    }

    /**
     * The XEYE list of an organization, created on first use (or re-linked when it already exists
     * remotely, e.g. after restoring the database).
     */
    private SearchList ensureList(Organization org) {
        SearchList known = lists.findById(org.getId()).orElse(null);
        if (known != null) return known;
        String name = properties.listNameFor(org.getId());
        XeyeList remote = client.findList(name)
                .orElseGet(() -> client.createList(name, "Stocka · " + org.getName()));
        return lists.save(new SearchList()
                .setOrganizationId(org.getId())
                .setXeyeListId(remote.id())
                .setListName(name));
    }

    private void markPending(SearchList list) {
        LocalDateTime now = now();
        if (list.getPendingSince() == null) {
            list.setPendingSince(now);
        }
        lists.save(list.setLastChangeAt(now));
    }

    private ElementInput toInput(Piece piece) {
        return PieceSearchDocument.of(piece, pieceService.valuesOf(piece), pieceService.orgValuesOf(piece))
                .toElementInput();
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
