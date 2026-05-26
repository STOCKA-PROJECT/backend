package com.stocka.backend.modules.auth.service;

import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.stocka.backend.modules.auth.dto.LoginUserDto;
import com.stocka.backend.modules.auth.dto.RegisterUserDto;
import com.stocka.backend.modules.auth.entity.RefreshToken.RevocationReason;
import com.stocka.backend.modules.common.dto.AvailabilityResponse;
import com.stocka.backend.modules.common.error.ApiException;
import com.stocka.backend.modules.common.error.ErrorCodes;
import com.stocka.backend.modules.roles.entity.Role;
import com.stocka.backend.modules.roles.entity.RoleEnum;
import com.stocka.backend.modules.roles.repository.RoleRepository;
import com.stocka.backend.modules.security.audit.SecurityAuditService;
import com.stocka.backend.modules.security.audit.SecurityEventType;
import com.stocka.backend.modules.security.entity.InvalidatedToken;
import com.stocka.backend.modules.security.repository.InvalidatedTokenRepository;
import com.stocka.backend.modules.security.service.JwtService;
import com.stocka.backend.modules.users.entity.Language;
import com.stocka.backend.modules.users.entity.User;
import com.stocka.backend.modules.users.repository.UserRepository;
import com.stocka.backend.modules.users.service.UserService;

@Service
public class AuthenticationService {

    private final UserRepository userRepository;
    private final UserService userService;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final InvalidatedTokenRepository invalidatedTokenRepository;
    private final JwtService jwtService;
    private final EmailVerificationService emailVerificationService;
    private final RefreshTokenService refreshTokenService;
    private final SecurityAuditService securityAuditService;

    public AuthenticationService(
            UserRepository userRepository,
            UserService userService,
            RoleRepository roleRepository,
            PasswordEncoder passwordEncoder,
            AuthenticationManager authenticationManager,
            InvalidatedTokenRepository invalidatedTokenRepository,
            JwtService jwtService,
            EmailVerificationService emailVerificationService,
            RefreshTokenService refreshTokenService,
            SecurityAuditService securityAuditService
    ) {
        this.userRepository = userRepository;
        this.userService = userService;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.invalidatedTokenRepository = invalidatedTokenRepository;
        this.jwtService = jwtService;
        this.emailVerificationService = emailVerificationService;
        this.refreshTokenService = refreshTokenService;
        this.securityAuditService = securityAuditService;
    }

    public User signup(RegisterUserDto input) {
        if (!input.getPassword().equals(input.getRepeatPassword())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.AUTH_PASSWORDS_MISMATCH);
        }

        Language language = Language.fromString(input.getLanguage());

        if (userRepository.findByEmail(input.getEmail()).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.USERS_EMAIL_TAKEN);
        }

        AvailabilityResponse usernameCheck = userService.checkUsernameAvailability(input.getUsername());
        if (!usernameCheck.available()) {
            switch (usernameCheck.reason()) {
                case INVALID_FORMAT -> throw new ApiException(
                        HttpStatus.BAD_REQUEST, ErrorCodes.USERS_USERNAME_INVALID);
                case RESERVED -> throw new ApiException(
                        HttpStatus.BAD_REQUEST, ErrorCodes.USERS_USERNAME_RESERVED);
                case TAKEN -> throw new ApiException(
                        HttpStatus.CONFLICT, ErrorCodes.USERS_USERNAME_TAKEN);
            }
        }

        Optional<Role> optionalRole = roleRepository.findByName(RoleEnum.USER);

        if (optionalRole.isEmpty()) {
            throw new IllegalStateException("Role USER no existe en la base de datos");
        }

        User user = new User()
                .setName(input.getName())
                .setLastName(input.getLastName())
                .setUsername(input.getUsername())
                .setEmail(input.getEmail())
                .setPassword(passwordEncoder.encode(input.getPassword()))
                .setRole(optionalRole.get())
                .setEmailVerified(false)
                .setLanguage(language);

        User saved = userRepository.save(user);
        emailVerificationService.sendVerificationEmail(saved);
        return saved;
    }

    /**
     * Checks whether the given username can be used to register a new user. Delegates to
     * {@link UserService#checkUsernameAvailability(String)} so signup, profile-rename and the
     * standalone availability endpoint all share the same rules (format, reserved list,
     * active uniqueness and historical usernames whose owner is still active).
     *
     * @param username candidate username; may be {@code null}
     * @return an {@link AvailabilityResponse} describing the result; never {@code null}
     */
    public AvailabilityResponse checkUsernameAvailability(String username) {
        return userService.checkUsernameAvailability(username);
    }

    /**
     * Invalidates an access token (adds it to {@code invalidated_tokens}) and
     * revokes the matching refresh token, if any.
     *
     * @param accessToken JWT pulled from the {@code Authorization} header; required
     * @param rawRefreshToken raw refresh token from the {@code stocka_refresh} cookie;
     *                        may be {@code null} (e.g. when the cookie was already cleared)
     */
    public void logout(String accessToken, String rawRefreshToken) {
        invalidatedTokenRepository.deleteExpired(java.time.LocalDateTime.now());
        InvalidatedToken invalidatedToken = new InvalidatedToken()
                .setToken(accessToken)
                .setExpiresAt(jwtService.extractExpirationAsLocalDateTime(accessToken));
        invalidatedTokenRepository.save(invalidatedToken);
        refreshTokenService.revokeByRawToken(rawRefreshToken);

        String email = jwtService.extractUsername(accessToken);
        User user = email != null ? userRepository.findByEmail(email).orElse(null) : null;
        securityAuditService.recordSuccess(SecurityEventType.LOGOUT, user);
    }

    /**
     * Revokes every active refresh token for the user. Called after a
     * password change so a leaked password cannot keep minting access
     * tokens through the refresh endpoint.
     *
     * @param user the user whose sessions should be revoked
     */
    public void revokeAllSessions(User user) {
        refreshTokenService.revokeAllForUser(user, RevocationReason.PASSWORD_CHANGED);
    }

    /**
     * Verifies password + email-verified. Does <em>not</em> record
     * {@code LOGIN_SUCCESS} — the controller does so after the optional 2FA
     * step succeeds (otherwise we'd be recording successful logins on
     * accounts where the second factor was never presented).
     */
    public User authenticate(LoginUserDto input) {
        User user;
        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(input.getEmail(), input.getPassword())
            );
            user = (User) authentication.getPrincipal();
        } catch (AuthenticationException ex) {
            User known = userRepository.findByEmail(input.getEmail()).orElse(null);
            securityAuditService.recordFailure(SecurityEventType.LOGIN_FAILED, known, input.getEmail());
            throw ex;
        }
        if (!user.isEmailVerified()) {
            securityAuditService.recordFailure(
                    SecurityEventType.LOGIN_FAILED, user, input.getEmail(),
                    "{\"reason\":\"email_not_verified\"}");
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.AUTH_EMAIL_NOT_VERIFIED);
        }
        return user;
    }
}
