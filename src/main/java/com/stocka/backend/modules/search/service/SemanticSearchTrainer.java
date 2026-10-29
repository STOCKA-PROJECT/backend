package com.stocka.backend.modules.search.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.stocka.backend.modules.search.SemanticSearchProperties;
import com.stocka.backend.modules.search.client.XeyeClient;
import com.stocka.backend.modules.search.client.XeyeClient.Training;
import com.stocka.backend.modules.search.client.XeyeException;
import com.stocka.backend.modules.search.entity.SearchList;
import com.stocka.backend.modules.search.repository.SearchListRepository;

/**
 * Launches one XEYE training per list once its changes have settled, like the activity digest.
 *
 * <p>XEYE never trains by itself: elements edited after the last training only match by text
 * until a new one runs. Two scheduled entry points launch it:
 * <ul>
 *   <li>a tick every {@code stocka.search.xeye.training-check-ms} trains the pending lists whose
 *       <em>last</em> change is older than {@link SemanticSearchProperties#getTrainingDelay()}
 *       (30 min), so a burst of edits costs one training however long it lasts;</li>
 *   <li>a cron at {@code stocka.search.xeye.training-cron} (midnight in
 *       {@code stocka.search.xeye.training-zone}) trains every list still pending, so a day of
 *       edits that never go quiet is trained at least once a day.</li>
 * </ul>
 * A list whose training is already running (409) or whose launch failed for a temporary reason
 * stays pending and is retried by the next tick once quiet; a launch XEYE rejects for good (for
 * example an empty list) clears the flag.
 */
@Component
public class SemanticSearchTrainer {

    private static final Logger log = LoggerFactory.getLogger(SemanticSearchTrainer.class);

    private final SemanticSearchProperties properties;
    private final XeyeClient client;
    private final SearchListRepository lists;
    private final Clock clock;

    @Autowired
    public SemanticSearchTrainer(SemanticSearchProperties properties, XeyeClient client, SearchListRepository lists) {
        this(properties, client, lists, Clock.systemDefaultZone());
    }

    SemanticSearchTrainer(SemanticSearchProperties properties, XeyeClient client, SearchListRepository lists,
                          Clock clock) {
        this.properties = properties;
        this.client = client;
        this.lists = lists;
        this.clock = clock;
    }

    /** Tick: launches the trainings of the pending lists whose edits have gone quiet. */
    @Scheduled(fixedDelayString = "${stocka.search.xeye.training-check-ms:60000}",
            initialDelayString = "${stocka.search.xeye.training-check-ms:60000}")
    public void launchDueTrainings() {
        if (!properties.isConfigured()) return;
        LocalDateTime cutoff = LocalDateTime.now(clock).minus(properties.getTrainingDelay());
        launchAll(lists.findQuietPending(cutoff));
    }

    /** Nightly: launches the training of every list still pending, quiet or not. */
    @Scheduled(cron = "${stocka.search.xeye.training-cron:0 0 0 * * *}",
            zone = "${stocka.search.xeye.training-zone:Europe/Madrid}")
    public void launchPendingTrainings() {
        if (!properties.isConfigured()) return;
        launchAll(lists.findByPendingSinceIsNotNull());
    }

    private void launchAll(List<SearchList> pending) {
        for (SearchList list : pending) {
            launch(list);
        }
    }

    /**
     * Launches the training of one list and records the outcome.
     *
     * @param list a list with a pending training
     */
    void launch(SearchList list) {
        try {
            Training training = client.launchTraining(list.getXeyeListId());
            lists.save(list.setPendingSince(null)
                    .setLastTrainingLaunchedAt(LocalDateTime.now(clock))
                    .setLastTrainingId(training.id()));
            log.info("semantic_training_launched org={} trainingId={} status={}",
                    list.getOrganizationId(), training.id(), training.status());
        } catch (XeyeException ex) {
            if (ex.isConflict()) {
                log.debug("semantic_training_busy org={}: {}", list.getOrganizationId(), ex.getMessage());
            } else if (ex.isRetryable() || ex.status() == 402) {
                log.warn("semantic_training_deferred org={} status={} code={}: {}",
                        list.getOrganizationId(), ex.status(), ex.code(), ex.getMessage());
            } else {
                log.warn("semantic_training_rejected org={} status={} code={}: {}",
                        list.getOrganizationId(), ex.status(), ex.code(), ex.getMessage());
                lists.save(list.setPendingSince(null));
            }
        }
    }
}
