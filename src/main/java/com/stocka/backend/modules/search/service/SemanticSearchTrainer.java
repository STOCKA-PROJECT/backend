package com.stocka.backend.modules.search.service;

import java.time.Clock;
import java.time.LocalDateTime;

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
 * Launches one XEYE training per list once its changes have settled.
 *
 * <p>XEYE never trains by itself: elements edited after the last training only match by text
 * until a new one runs. This job looks every {@code stocka.search.xeye.training-check-ms} for
 * lists pending for longer than {@link SemanticSearchProperties#getTrainingDelay()} and launches
 * their training. A list whose training is already running (409) or whose launch failed for a
 * temporary reason stays pending and is retried on the next tick; a launch XEYE rejects for good
 * (for example an empty list) clears the flag.
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

    /** Scheduled entry point: launches the trainings whose debounce window has elapsed. */
    @Scheduled(fixedDelayString = "${stocka.search.xeye.training-check-ms:60000}",
            initialDelayString = "${stocka.search.xeye.training-check-ms:60000}")
    public void launchDueTrainings() {
        if (!properties.isConfigured()) return;
        LocalDateTime cutoff = LocalDateTime.now(clock).minus(properties.getTrainingDelay());
        for (SearchList list : lists.findByPendingSinceBefore(cutoff)) {
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
