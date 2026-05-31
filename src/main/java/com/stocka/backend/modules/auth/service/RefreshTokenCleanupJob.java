package com.stocka.backend.modules.auth.service;

import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.stocka.backend.modules.auth.repository.RefreshTokenRepository;

/**
 * Nightly purge of expired refresh tokens. Mirrors {@link
 * EmailVerificationTokenCleanupJob} so the schedules can be tuned independently
 * (refresh tokens accumulate quickly — one row per login per day).
 */
@Component
public class RefreshTokenCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenCleanupJob.class);

    private final RefreshTokenRepository repository;

    public RefreshTokenCleanupJob(RefreshTokenRepository repository) {
        this.repository = repository;
    }

    @Scheduled(cron = "${security.refresh.cleanup-cron:0 15 3 * * *}")
    public void purgeExpiredTokens() {
        int removed = repository.deleteByExpiresAtBefore(LocalDateTime.now());
        if (removed > 0) {
            log.info("[REFRESH-TOKENS] purged {} expired tokens", removed);
        }
    }
}
