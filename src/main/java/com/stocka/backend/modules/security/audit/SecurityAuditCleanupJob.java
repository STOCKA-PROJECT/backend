package com.stocka.backend.modules.security.audit;

import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.stocka.backend.modules.security.repository.SecurityAuditEntryRepository;

/**
 * Nightly purge of audit entries past the retention horizon (180 days by
 * default). Aligns with most GDPR-style "minimal retention" expectations
 * without losing the forensic value of a multi-month window.
 */
@Component
public class SecurityAuditCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(SecurityAuditCleanupJob.class);

    private final SecurityAuditEntryRepository repository;
    private final int retentionDays;

    public SecurityAuditCleanupJob(
            SecurityAuditEntryRepository repository,
            @Value("${security.audit.retention-days:180}") int retentionDays) {
        this.repository = repository;
        this.retentionDays = retentionDays;
    }

    @Scheduled(cron = "${security.audit.cleanup-cron:0 45 3 * * *}")
    public void purgeExpiredEntries() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        int removed = repository.deleteByCreatedAtBefore(cutoff);
        if (removed > 0) {
            log.info("[SECURITY-AUDIT] purged {} entries older than {} days", removed, retentionDays);
        }
    }
}
