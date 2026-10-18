package com.stocka.backend.modules.search.listener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import com.stocka.backend.modules.notifications.events.ResourceKind;
import com.stocka.backend.modules.notifications.events.ResourceLifecycleEvent;
import com.stocka.backend.modules.notifications.preferences.entity.LifecycleAction;
import com.stocka.backend.modules.search.SemanticSearchProperties;
import com.stocka.backend.modules.search.service.SemanticSearchIndexer;

/**
 * Mirrors piece changes into XEYE after the transaction that made them commits.
 *
 * <p>Runs asynchronously so a slow or failing search service never delays or breaks a piece
 * request: a failure is logged and the piece is picked up by the next edit or by a reindex.
 */
@Component
public class PieceLifecycleSearchListener {

    private static final Logger log = LoggerFactory.getLogger(PieceLifecycleSearchListener.class);

    private final SemanticSearchProperties properties;
    private final SemanticSearchIndexer indexer;

    public PieceLifecycleSearchListener(SemanticSearchProperties properties, SemanticSearchIndexer indexer) {
        this.properties = properties;
        this.indexer = indexer;
    }

    /**
     * Indexes created and edited pieces and removes deleted ones.
     *
     * @param event the committed lifecycle event
     */
    @Async
    @TransactionalEventListener
    public void onResourceLifecycle(ResourceLifecycleEvent event) {
        if (event.kind() != ResourceKind.PIECE || !properties.isConfigured()) return;
        try {
            if (event.action() == LifecycleAction.DELETED) {
                indexer.remove(event.resourceId());
            } else {
                indexer.index(event.resourceId());
            }
        } catch (RuntimeException ex) {
            log.warn("semantic_index_failed action={} pieceId={}: {}",
                    event.action(), event.resourceId(), ex.getMessage());
        }
    }
}
