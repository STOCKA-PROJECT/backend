package com.stocka.backend.modules.auth.service;

import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.stocka.backend.modules.auth.repository.EmailVerificationTokenRepository;

/**
 * Nightly purge of expired email verification tokens. Decoupled from the password-reset
 * cleanup job so the two flows can be tuned (TTL, cron) independently.
 */
@Component
public class EmailVerificationTokenCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationTokenCleanupJob.class);

    private final EmailVerificationTokenRepository tokenRepository;

    public EmailVerificationTokenCleanupJob(EmailVerificationTokenRepository tokenRepository) {
        this.tokenRepository = tokenRepository;
    }

    @Scheduled(cron = "${app.email-verification.cleanup-cron:0 30 3 * * *}")
    public void purgeExpiredTokens() {
        int removed = tokenRepository.deleteByExpiresAtBefore(LocalDateTime.now());
        if (removed > 0) {
            log.info("[EMAIL-VERIFICATION] purged {} expired tokens", removed);
        }
    }
}
