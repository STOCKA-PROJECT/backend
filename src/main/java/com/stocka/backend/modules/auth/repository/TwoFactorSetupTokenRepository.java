package com.stocka.backend.modules.auth.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.stocka.backend.modules.auth.entity.TwoFactorSetupToken;
import com.stocka.backend.modules.users.entity.User;

@Repository
public interface TwoFactorSetupTokenRepository extends JpaRepository<TwoFactorSetupToken, Long> {

    Optional<TwoFactorSetupToken> findBySetupTokenHash(String setupTokenHash);

    @Modifying
    @Transactional
    @Query("DELETE FROM TwoFactorSetupToken t WHERE t.user = :user")
    int deleteAllByUser(@Param("user") User user);

    @Modifying
    @Transactional
    @Query("DELETE FROM TwoFactorSetupToken t WHERE t.expiresAt < :cutoff OR t.consumed = true")
    int deleteExpiredOrConsumed(@Param("cutoff") LocalDateTime cutoff);
}
