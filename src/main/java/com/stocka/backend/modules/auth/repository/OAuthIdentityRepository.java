package com.stocka.backend.modules.auth.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.stocka.backend.modules.auth.entity.OAuthIdentity;
import com.stocka.backend.modules.auth.entity.OAuthIdentity.Provider;
import com.stocka.backend.modules.users.entity.User;

@Repository
public interface OAuthIdentityRepository extends JpaRepository<OAuthIdentity, Long> {

    Optional<OAuthIdentity> findByProviderAndProviderUserId(Provider provider, String providerUserId);

    List<OAuthIdentity> findByUser(User user);

    Optional<OAuthIdentity> findByUserAndProvider(User user, Provider provider);
}
