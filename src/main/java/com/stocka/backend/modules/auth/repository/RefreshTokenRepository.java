package com.stocka.backend.modules.auth.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.stocka.backend.modules.auth.entity.RefreshToken;
import com.stocka.backend.modules.auth.entity.RefreshToken.RevocationReason;
import com.stocka.backend.modules.users.entity.User;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Integer> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Bulk-revoke every still-active token in a family. Used when reuse is detected
     * — the entire chain is assumed compromised.
     *
     * @param familyId family identifier shared by every rotation of a single login
     * @param reason   why the family is being revoked
     * @param now      timestamp to stamp on the revoked rows
     * @return number of rows affected
     */
    @Modifying
    @Transactional
    @Query("UPDATE RefreshToken t SET t.revokedAt = :now, t.revokedReason = :reason "
            + "WHERE t.familyId = :familyId AND t.revokedAt IS NULL")
    int revokeFamily(@Param("familyId") String familyId,
                     @Param("reason") RevocationReason reason,
                     @Param("now") LocalDateTime now);

    /**
     * Bulk-revoke every still-active token belonging to a user. Used on password
     * change and on the future "revoke all sessions" admin endpoint.
     *
     * @param user   the user whose sessions are being terminated
     * @param reason why the sessions are being revoked
     * @param now    timestamp to stamp on the revoked rows
     * @return number of rows affected
     */
    @Modifying
    @Transactional
    @Query("UPDATE RefreshToken t SET t.revokedAt = :now, t.revokedReason = :reason "
            + "WHERE t.user = :user AND t.revokedAt IS NULL")
    int revokeAllForUser(@Param("user") User user,
                         @Param("reason") RevocationReason reason,
                         @Param("now") LocalDateTime now);

    /**
     * Nightly cleanup. Deletes refresh tokens whose expiration window is already
     * behind us regardless of revocation state — the audit log is the long-term
     * record, not this table.
     *
     * @param cutoff anything older than this gets removed
     * @return number of rows deleted
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM RefreshToken t WHERE t.expiresAt < :cutoff")
    int deleteByExpiresAtBefore(@Param("cutoff") LocalDateTime cutoff);
}
