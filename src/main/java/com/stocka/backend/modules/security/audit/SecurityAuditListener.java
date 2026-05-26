package com.stocka.backend.modules.security.audit;

import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import com.stocka.backend.modules.security.entity.SecurityAuditEntry;
import com.stocka.backend.modules.security.repository.SecurityAuditEntryRepository;
import com.stocka.backend.modules.users.entity.User;
import com.stocka.backend.modules.users.repository.UserRepository;

/**
 * Async sink for {@link SecurityAuditEvent}. Runs on the default Spring
 * {@code @Async} executor and is intentionally tolerant: a failure to write
 * the row gets logged but never bubbles back to the caller.
 *
 * <p>The user reference is re-resolved here (instead of being carried in the
 * event) because Hibernate-managed entities can't safely cross thread
 * boundaries.
 */
@Component
public class SecurityAuditListener {

    private static final Logger log = LoggerFactory.getLogger(SecurityAuditListener.class);

    private final SecurityAuditEntryRepository repository;
    private final UserRepository userRepository;

    public SecurityAuditListener(SecurityAuditEntryRepository repository, UserRepository userRepository) {
        this.repository = repository;
        this.userRepository = userRepository;
    }

    @Async
    @EventListener
    public void on(SecurityAuditEvent event) {
        try {
            User user = event.userId() != null
                    ? userRepository.findById(event.userId()).orElse(null)
                    : null;
            SecurityAuditEntry entry = new SecurityAuditEntry()
                    .setUser(user)
                    .setEmail(event.email())
                    .setEventType(event.eventType())
                    .setIpAddress(event.ipAddress())
                    .setUserAgent(event.userAgent())
                    .setMetadata(event.metadata())
                    .setSuccess(event.success())
                    .setCreatedAt(LocalDateTime.now());
            repository.save(entry);
        } catch (RuntimeException ex) {
            log.warn("security_audit_persist_failed event={} user_id={}",
                    event.eventType(), event.userId(), ex);
        }
    }
}
