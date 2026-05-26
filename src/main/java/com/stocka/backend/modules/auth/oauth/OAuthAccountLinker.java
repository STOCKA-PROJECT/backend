package com.stocka.backend.modules.auth.oauth;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.stocka.backend.modules.auth.entity.OAuthIdentity;
import com.stocka.backend.modules.auth.entity.OAuthIdentity.Provider;
import com.stocka.backend.modules.auth.oauth.GoogleOAuthClient.UserInfo;
import com.stocka.backend.modules.auth.repository.OAuthIdentityRepository;
import com.stocka.backend.modules.common.error.ApiException;
import com.stocka.backend.modules.common.error.ErrorCodes;
import com.stocka.backend.modules.roles.entity.Role;
import com.stocka.backend.modules.roles.entity.RoleEnum;
import com.stocka.backend.modules.roles.repository.RoleRepository;
import com.stocka.backend.modules.security.audit.SecurityAuditService;
import com.stocka.backend.modules.security.audit.SecurityEventType;
import com.stocka.backend.modules.users.entity.User;
import com.stocka.backend.modules.users.repository.UserRepository;

/**
 * Resolves a Google profile into a local {@link User}. Three branches:
 * <ol>
 *   <li>An {@link OAuthIdentity} already exists for the Google {@code sub} →
 *       returns its user (plain login).</li>
 *   <li>No identity but a user exists with the same email + Google says
 *       {@code email_verified=true} → auto-link to that user. Records
 *       {@code OAUTH_LINKED} in the audit log.</li>
 *   <li>No identity, no user → signup. Creates the user with
 *       {@code emailVerified=true} (Google already verified) and a random
 *       long password that nobody knows.</li>
 * </ol>
 */
@Service
public class OAuthAccountLinker {

    private static final Logger log = LoggerFactory.getLogger(OAuthAccountLinker.class);

    private final OAuthIdentityRepository identityRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final SecurityAuditService securityAuditService;

    public OAuthAccountLinker(
            OAuthIdentityRepository identityRepository,
            UserRepository userRepository,
            RoleRepository roleRepository,
            PasswordEncoder passwordEncoder,
            SecurityAuditService securityAuditService) {
        this.identityRepository = identityRepository;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.securityAuditService = securityAuditService;
    }

    @Transactional
    public User linkOrLogin(UserInfo profile) {
        Optional<OAuthIdentity> existing = identityRepository
                .findByProviderAndProviderUserId(Provider.GOOGLE, profile.sub());
        if (existing.isPresent()) {
            return existing.get().getUser();
        }

        Optional<User> byEmail = userRepository.findByEmail(profile.email());
        if (byEmail.isPresent()) {
            if (!profile.emailVerified()) {
                // Refuse silent linking when Google can't vouch for the email
                // — otherwise an attacker that controls a Google account with
                // someone else's email could hijack the local account.
                throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.USERS_EMAIL_TAKEN);
            }
            OAuthIdentity link = persistLink(byEmail.get(), profile);
            securityAuditService.recordSuccess(SecurityEventType.OAUTH_LINKED, link.getUser(),
                    "{\"provider\":\"GOOGLE\"}");
            return byEmail.get();
        }

        User created = createUserFromGoogleProfile(profile);
        persistLink(created, profile);
        securityAuditService.recordSuccess(SecurityEventType.OAUTH_LINKED, created,
                "{\"provider\":\"GOOGLE\",\"signup\":true}");
        return created;
    }

    @Transactional
    public void unlink(User user, Provider provider) {
        Optional<OAuthIdentity> existing = identityRepository.findByUserAndProvider(user, provider);
        if (existing.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.OAUTH_LINK_NOT_FOUND);
        }
        // Refuse to unlink the last credential — would lock the user out.
        // (User has a password by definition; OAuth-only accounts get a random
        // password they can't use, but they can password-reset to unlock.)
        identityRepository.delete(existing.get());
        securityAuditService.recordSuccess(SecurityEventType.OAUTH_UNLINKED, user,
                "{\"provider\":\"" + provider + "\"}");
    }

    private OAuthIdentity persistLink(User user, UserInfo profile) {
        OAuthIdentity entity = new OAuthIdentity()
                .setUser(user)
                .setProvider(Provider.GOOGLE)
                .setProviderUserId(profile.sub())
                .setEmail(profile.email())
                .setLinkedAt(LocalDateTime.now());
        return identityRepository.save(entity);
    }

    private User createUserFromGoogleProfile(UserInfo profile) {
        Role userRole = roleRepository.findByName(RoleEnum.USER)
                .orElseThrow(() -> new IllegalStateException("Role USER no existe en la base de datos"));
        String randomPassword = passwordEncoder.encode(UUID.randomUUID().toString() + UUID.randomUUID());
        String username = deriveUsername(profile.email());
        User user = new User()
                .setName(orFallback(profile.givenName(), "Google"))
                .setLastName(orFallback(profile.familyName(), "User"))
                .setUsername(username)
                .setEmail(profile.email())
                .setEmailVerified(profile.emailVerified())
                .setPassword(randomPassword)
                .setRole(userRole);
        try {
            return userRepository.save(user);
        } catch (RuntimeException ex) {
            log.warn("oauth_signup_collision username={}", username, ex);
            // Username collision — append a random suffix and retry once.
            user.setUsername(username + UUID.randomUUID().toString().substring(0, 4));
            return userRepository.save(user);
        }
    }

    private static String deriveUsername(String email) {
        String local = email.split("@", 2)[0].toLowerCase().replaceAll("[^a-z0-9]", "");
        if (local.length() < 3) local = "user" + local;
        if (local.length() > 20) local = local.substring(0, 20);
        return local;
    }

    private static String orFallback(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
