package com.stocka.backend.modules.search.service;

import java.util.HashSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.repository.OrganizationRepository;
import com.stocka.backend.modules.search.SemanticSearchProperties;
import com.stocka.backend.modules.search.entity.SearchList;
import com.stocka.backend.modules.search.repository.SearchListRepository;

/**
 * Indexes, once the application is up, every organization that has no XEYE list yet.
 *
 * <p>This is what makes enabling the feature on an existing installation self-contained: the
 * first start with a key backfills all organizations without anyone calling the admin endpoint,
 * and later starts only touch organizations created while the feature was off (a new organization
 * normally gets its list with its first piece). Runs asynchronously so startup is never delayed,
 * and one organization failing does not stop the others.
 */
@Component
public class SemanticSearchBootstrap {

    private static final Logger log = LoggerFactory.getLogger(SemanticSearchBootstrap.class);

    private final SemanticSearchProperties properties;
    private final SemanticSearchIndexer indexer;
    private final SearchListRepository lists;
    private final OrganizationRepository organizations;

    public SemanticSearchBootstrap(SemanticSearchProperties properties, SemanticSearchIndexer indexer,
                                   SearchListRepository lists, OrganizationRepository organizations) {
        this.properties = properties;
        this.indexer = indexer;
        this.lists = lists;
        this.organizations = organizations;
    }

    /** Startup hook: backfills in the background when the feature is configured. */
    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!properties.isConfigured()) return;
        backfillMissing();
    }

    /**
     * Rebuilds the index of every active organization without a XEYE list.
     *
     * @return how many organizations were indexed (failures are logged and skipped)
     */
    public int backfillMissing() {
        Set<Integer> indexed = new HashSet<>();
        for (SearchList list : lists.findAll()) {
            indexed.add(list.getOrganizationId());
        }
        int done = 0;
        for (Organization org : organizations.findAll()) {
            if (indexed.contains(org.getId())) continue;
            try {
                indexer.reindex(org.getId());
                done++;
            } catch (RuntimeException ex) {
                log.warn("semantic_backfill_failed org={}: {}", org.getId(), ex.getMessage());
            }
        }
        if (done > 0) {
            log.info("semantic_backfill organizations={}", done);
        }
        return done;
    }
}
