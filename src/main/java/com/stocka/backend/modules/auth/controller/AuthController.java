package com.stocka.backend.modules.auth.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.stocka.backend.modules.auth.dto.ForgotPasswordRequestDto;
import com.stocka.backend.modules.auth.dto.LoginResponseDto;
import com.stocka.backend.modules.auth.dto.LoginUserDto;
import com.stocka.backend.modules.auth.dto.RegisterUserDto;
import com.stocka.backend.modules.auth.dto.ResendVerificationRequestDto;
import com.stocka.backend.modules.auth.dto.ResetPasswordRequestDto;
import com.stocka.backend.modules.auth.dto.TwoFactorChallengeRequestDto;
import com.stocka.backend.modules.auth.dto.TwoFactorConfirmRequestDto;
import com.stocka.backend.modules.auth.dto.TwoFactorDisableRequestDto;
import com.stocka.backend.modules.auth.dto.TwoFactorRecoveryCodesResponseDto;
import com.stocka.backend.modules.auth.dto.TwoFactorSetupResponseDto;
import com.stocka.backend.modules.auth.dto.VerifyEmailRequestDto;
import com.stocka.backend.modules.auth.service.AuthenticationService;
import com.stocka.backend.modules.auth.service.EmailVerificationService;
import com.stocka.backend.modules.auth.service.PasswordResetService;
import com.stocka.backend.modules.auth.service.RefreshTokenCookieFactory;
import com.stocka.backend.modules.auth.service.RefreshTokenService;
import com.stocka.backend.modules.auth.service.RefreshTokenService.IssuedRefreshToken;
import com.stocka.backend.modules.auth.twofactor.TwoFactorService;
import com.stocka.backend.modules.common.dto.AvailabilityResponse;
import com.stocka.backend.modules.common.error.ApiException;
import com.stocka.backend.modules.common.error.ErrorCodes;
import com.stocka.backend.modules.security.audit.SecurityAuditService;
import com.stocka.backend.modules.security.audit.SecurityEventType;
import com.stocka.backend.modules.security.ratelimit.ClientIpResolver;
import com.stocka.backend.modules.security.service.JwtService;
import com.stocka.backend.modules.users.dto.UserResponseDto;
import com.stocka.backend.modules.users.entity.User;
import com.stocka.backend.modules.users.repository.UserRepository;
import com.stocka.backend.modules.users.service.UserDeviceService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/auth")
public class AuthController {
    private final AuthenticationService authenticationService;
    private final PasswordResetService passwordResetService;
    private final EmailVerificationService emailVerificationService;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final RefreshTokenCookieFactory refreshCookieFactory;
    private final UserDeviceService userDeviceService;
    private final ClientIpResolver clientIpResolver;
    private final TwoFactorService twoFactorService;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final SecurityAuditService securityAuditService;
    private final long mfaChallengeTtlSeconds;

    public AuthController(
            AuthenticationService authenticationService,
            PasswordResetService passwordResetService,
            EmailVerificationService emailVerificationService,
            JwtService jwtService,
            RefreshTokenService refreshTokenService,
            RefreshTokenCookieFactory refreshCookieFactory,
            UserDeviceService userDeviceService,
            ClientIpResolver clientIpResolver,
            TwoFactorService twoFactorService,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            SecurityAuditService securityAuditService,
            @Value("${security.twofactor.mfa-challenge-ttl-seconds:300}") long mfaChallengeTtlSeconds
    ) {
        this.authenticationService = authenticationService;
        this.passwordResetService = passwordResetService;
        this.emailVerificationService = emailVerificationService;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
        this.refreshCookieFactory = refreshCookieFactory;
        this.userDeviceService = userDeviceService;
        this.clientIpResolver = clientIpResolver;
        this.twoFactorService = twoFactorService;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.securityAuditService = securityAuditService;
        this.mfaChallengeTtlSeconds = mfaChallengeTtlSeconds;
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @RequestHeader("Authorization") String authHeader,
            HttpServletRequest request
    ) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Token no proporcionado");
        }
        String accessToken = authHeader.substring(7);
        String rawRefresh = refreshCookieFactory.readFromRequest(request).orElse(null);
        authenticationService.logout(accessToken, rawRefresh);

        ResponseCookie clearing = refreshCookieFactory.buildClearing();
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, clearing.toString())
                .build();
    }

    @PostMapping("/signup")
    public ResponseEntity<UserResponseDto> signup(@Valid @RequestBody RegisterUserDto registerUserDto) {
        User registeredUser = authenticationService.signup(registerUserDto);
        return ResponseEntity.ok(UserResponseDto.from(registeredUser));
    }

    /**
     * Returns whether the given username is available for registration.
     *
     * @param username candidate username
     * @return availability result with reason when unavailable
     */
    @GetMapping("/check-username")
    public AvailabilityResponse checkUsername(@RequestParam String username) {
        return authenticationService.checkUsernameAvailability(username);
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponseDto> login(
            @Valid @RequestBody LoginUserDto loginUserDto,
            HttpServletRequest request) {
        User authenticatedUser = authenticationService.authenticate(loginUserDto);
        if (authenticatedUser.isTwoFactorEnabled()) {
            String mfaToken = jwtService.generateMfaChallengeToken(
                    authenticatedUser.getEmail(), mfaChallengeTtlSeconds);
            return ResponseEntity.ok(new LoginResponseDto()
                    .setRequires2fa(true)
                    .setMfaToken(mfaToken));
        }
        return issueSession(authenticatedUser, loginUserDto.isRememberMe(), request);
    }

    /**
     * Second step of the 2FA login flow. Consumes the {@code mfaToken} from
     * {@link #login}, verifies the TOTP / recovery code, and on success
     * issues the same payload the no-2FA path produces.
     *
     * @param dto payload carrying the mfa token + code (+ remember-me)
     * @param request HTTP request — used for IP / UA capture on the device row
     * @return regular login response with access token and refresh cookie
     */
    @PostMapping("/login/2fa")
    public ResponseEntity<LoginResponseDto> loginTwoFactor(
            @Valid @RequestBody TwoFactorChallengeRequestDto dto,
            HttpServletRequest request) {
        if (!jwtService.isMfaChallengeValid(dto.getMfaToken())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.AUTH_2FA_MFA_TOKEN_EXPIRED);
        }
        String email = jwtService.extractUsername(dto.getMfaToken());
        User user = userRepository.findByEmail(email)
                .filter(User::isTwoFactorEnabled)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED,
                        ErrorCodes.AUTH_2FA_MFA_TOKEN_EXPIRED));
        if (!twoFactorService.verifyChallenge(user, dto.getCode())) {
            securityAuditService.recordFailure(
                    SecurityEventType.TWO_FACTOR_CHALLENGE_FAILED, user, email);
            throw new ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.AUTH_2FA_INVALID_CODE);
        }
        return issueSession(user, dto.isRememberMe(), request);
    }

    private ResponseEntity<LoginResponseDto> issueSession(
            User user, boolean rememberMe, HttpServletRequest request) {
        IssuedRefreshToken refresh = refreshTokenService.issueForLogin(user, rememberMe);
        userDeviceService.registerForLogin(
                user,
                refresh.entity().getFamilyId(),
                request.getHeader(HttpHeaders.USER_AGENT),
                clientIpResolver.resolve(request));
        String jwtToken = jwtService.generateToken(
                Map.of(JwtService.CLAIM_FAMILY_ID, refresh.entity().getFamilyId()),
                user);
        securityAuditService.recordSuccess(SecurityEventType.LOGIN_SUCCESS, user);

        LoginResponseDto body = new LoginResponseDto()
                .setAccessToken(jwtToken)
                .setExpiresIn(jwtService.getExpirationTime())
                .setUser(UserResponseDto.from(user));
        ResponseCookie cookie = refreshCookieFactory.build(refresh.rawToken(), rememberMe);

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(body);
    }

    /**
     * Starts the 2FA setup flow. Creates a candidate secret without changing
     * the user record — {@code /auth/2fa/confirm} is what actually flips
     * {@code twoFactorEnabled}.
     */
    @PostMapping("/2fa/setup")
    @org.springframework.security.access.prepost.PreAuthorize("isAuthenticated()")
    public ResponseEntity<TwoFactorSetupResponseDto> setupTwoFactor() {
        User user = currentUser();
        TwoFactorService.SetupResult result = twoFactorService.startSetup(user);
        return ResponseEntity.ok(new TwoFactorSetupResponseDto(
                result.setupToken(), result.secret(), result.otpAuthUri()));
    }

    /**
     * Confirms the setup. Returns the one-time visible recovery codes.
     */
    @PostMapping("/2fa/confirm")
    @org.springframework.security.access.prepost.PreAuthorize("isAuthenticated()")
    public ResponseEntity<TwoFactorRecoveryCodesResponseDto> confirmTwoFactor(
            @Valid @RequestBody TwoFactorConfirmRequestDto dto) {
        User user = currentUser();
        TwoFactorService.ConfirmResult result =
                twoFactorService.confirmSetup(user, dto.getSetupToken(), dto.getCode());
        securityAuditService.recordSuccess(SecurityEventType.TWO_FACTOR_ENABLED, user);
        return ResponseEntity.ok(new TwoFactorRecoveryCodesResponseDto(result.recoveryCodes()));
    }

    /**
     * Disables 2FA. Requires both the current password and a valid code (TOTP
     * or recovery) to prevent takeover from a hijacked session.
     */
    @PostMapping("/2fa/disable")
    @org.springframework.security.access.prepost.PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> disableTwoFactor(@Valid @RequestBody TwoFactorDisableRequestDto dto) {
        User user = currentUser();
        if (!passwordEncoder.matches(dto.getCurrentPassword(), user.getPassword())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.AUTH_CURRENT_PASSWORD_INVALID);
        }
        if (!twoFactorService.verifyChallenge(user, dto.getCode())) {
            securityAuditService.recordFailure(
                    SecurityEventType.TWO_FACTOR_CHALLENGE_FAILED, user, user.getEmail());
            throw new ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.AUTH_2FA_INVALID_CODE);
        }
        twoFactorService.disable(user);
        securityAuditService.recordSuccess(SecurityEventType.TWO_FACTOR_DISABLED, user);
        return ResponseEntity.noContent().build();
    }

    /**
     * Regenerates the recovery codes. Wipes the previous batch atomically;
     * returns the new codes (one-time visibility).
     */
    @PostMapping("/2fa/recovery-codes/regenerate")
    @org.springframework.security.access.prepost.PreAuthorize("isAuthenticated()")
    public ResponseEntity<TwoFactorRecoveryCodesResponseDto> regenerateRecoveryCodes() {
        User user = currentUser();
        if (!user.isTwoFactorEnabled()) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.AUTH_2FA_NOT_ENABLED);
        }
        List<String> codes = twoFactorService.generateRecoveryCodes(user);
        return ResponseEntity.ok(new TwoFactorRecoveryCodesResponseDto(codes));
    }

    private static User currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return (User) authentication.getPrincipal();
    }

    /**
     * Rotates the refresh token in the {@code stocka_refresh} cookie and mints
     * a fresh access token. The frontend hits this endpoint when an access
     * token comes back with 401 + {@code auth.token_expired}.
     *
     * @param request HTTP request — used only to read the cookie
     * @return new access token in the JSON body + rotated refresh in a new cookie
     */
    @PostMapping("/refresh")
    public ResponseEntity<LoginResponseDto> refresh(HttpServletRequest request) {
        String rawToken = refreshCookieFactory.readFromRequest(request)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "missing_refresh_cookie"));
        IssuedRefreshToken rotated = refreshTokenService.rotate(
                rawToken, request.getHeader(HttpHeaders.USER_AGENT));
        userDeviceService.touchOnRefresh(
                rotated.entity().getFamilyId(),
                clientIpResolver.resolve(request));

        User user = rotated.entity().getUser();
        String accessToken = jwtService.generateToken(
                Map.of(JwtService.CLAIM_FAMILY_ID, rotated.entity().getFamilyId()),
                user);
        LoginResponseDto body = new LoginResponseDto()
                .setAccessToken(accessToken)
                .setExpiresIn(jwtService.getExpirationTime())
                .setUser(UserResponseDto.from(user));
        ResponseCookie cookie = refreshCookieFactory.build(
                rotated.rawToken(), rotated.entity().isRememberMe());

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(body);
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequestDto dto) {
        passwordResetService.requestReset(dto.getEmail());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequestDto dto) {
        passwordResetService.resetPassword(dto);
        return ResponseEntity.noContent().build();
    }

    /**
     * Consumes a one-time email-verification token. On success the user's
     * {@code emailVerified} flag is flipped to {@code true} and the token is burned.
     *
     * @param dto request body carrying the raw token from the verification email
     * @return 204 on success
     */
    @PostMapping("/verify-email")
    public ResponseEntity<Void> verifyEmail(@Valid @RequestBody VerifyEmailRequestDto dto) {
        emailVerificationService.verify(dto.getToken());
        return ResponseEntity.noContent().build();
    }

    /**
     * Re-sends the verification email for the given address. Always responds with 204
     * to avoid leaking whether the email is registered or already verified.
     *
     * @param dto request body carrying the candidate email
     * @return 204 unconditionally
     */
    @PostMapping("/resend-verification")
    public ResponseEntity<Void> resendVerification(@Valid @RequestBody ResendVerificationRequestDto dto) {
        emailVerificationService.requestResend(dto.getEmail());
        return ResponseEntity.noContent().build();
    }
}
