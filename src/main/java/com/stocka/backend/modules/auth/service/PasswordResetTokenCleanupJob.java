package com.stocka.backend.modules.auth.service;

import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.stocka.backend.modules.auth.repository.PasswordResetTokenRepository;

@Component
public class PasswordResetTokenCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetTokenCleanupJob.class);

    private final PasswordResetTokenRepository tokenRepository;

    public PasswordResetTokenCleanupJob(PasswordResetTokenRepository tokenRepository) {
        this.tokenRepository = tokenRepository;
    }

    @Scheduled(cron = "${app.password-reset.cleanup-cron:0 0 3 * * *}")
    public void purgeExpiredTokens() {
        int removed = tokenRepository.deleteByExpiresAtBefore(LocalDateTime.now());
        if (removed > 0) {
            log.info("[PASSWORD-RESET] purged {} expired tokens", removed);
        }
    }
}
