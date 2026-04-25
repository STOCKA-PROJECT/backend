package com.stocka.backend.modules.auth.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.stocka.backend.modules.auth.entity.PasswordResetToken;
import com.stocka.backend.modules.users.entity.User;

@Repository
public interface PasswordResetTokenRepository extends CrudRepository<PasswordResetToken, Integer> {

    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    @Modifying
    @Transactional
    void deleteAllByUser(User user);

    @Modifying
    @Transactional
    @Query("DELETE FROM PasswordResetToken t WHERE t.expiresAt < :now")
    int deleteByExpiresAtBefore(LocalDateTime now);
}
