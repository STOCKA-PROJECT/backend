package com.stocka.backend.modules.security.repository;

import java.time.LocalDateTime;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.stocka.backend.modules.security.audit.SecurityEventType;
import com.stocka.backend.modules.security.entity.SecurityAuditEntry;
import com.stocka.backend.modules.users.entity.User;

@Repository
public interface SecurityAuditEntryRepository extends JpaRepository<SecurityAuditEntry, Long> {

    /**
     * Returns the user's audit entries of one type, newest first. Always
     * paginated — an account that's been around for a year can easily have
     * hundreds of logins.
     *
     * @param user account to query
     * @param eventType the only type returned
     * @param pageable page request
     * @return paginated audit entries
     */
    Page<SecurityAuditEntry> findByUserAndEventTypeOrderByCreatedAtDesc(
            User user, SecurityEventType eventType, Pageable pageable);

    /**
     * Drop entries older than the cutoff. Called nightly by the cleanup job.
     *
     * @param cutoff anything strictly older than this gets removed
     * @return number of rows deleted
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM SecurityAuditEntry e WHERE e.createdAt < :cutoff")
    int deleteByCreatedAtBefore(@Param("cutoff") LocalDateTime cutoff);
}
