package com.stocka.backend.modules.security.repository;

import com.stocka.backend.modules.security.entity.InvalidatedToken;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface InvalidatedTokenRepository extends CrudRepository<InvalidatedToken, Integer> {
    boolean existsByTokenAndExpiresAtAfter(String token, LocalDateTime now);

    @Modifying
    @Transactional
    @Query("DELETE FROM InvalidatedToken t WHERE t.expiresAt < :now")
    void deleteExpired(LocalDateTime now);
}
