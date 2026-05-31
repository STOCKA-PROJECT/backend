package com.stocka.backend.modules.auth.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.stocka.backend.modules.auth.entity.TwoFactorRecoveryCode;
import com.stocka.backend.modules.users.entity.User;

@Repository
public interface TwoFactorRecoveryCodeRepository extends JpaRepository<TwoFactorRecoveryCode, Long> {

    List<TwoFactorRecoveryCode> findByUserAndUsedAtIsNull(User user);

    @Modifying
    @Transactional
    @Query("DELETE FROM TwoFactorRecoveryCode c WHERE c.user = :user")
    int deleteAllByUser(@Param("user") User user);
}
