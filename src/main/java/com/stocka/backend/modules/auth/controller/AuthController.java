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
import com.stocka.backend.modules.auth.dto.VerifyEmailRequestDto;
import com.stocka.backend.modules.auth.service.AuthenticationService;
import com.stocka.backend.modules.auth.service.EmailVerificationService;
import com.stocka.backend.modules.auth.service.PasswordResetService;
import com.stocka.backend.modules.auth.service.RefreshTokenCookieFactory;
import com.stocka.backend.modules.auth.service.RefreshTokenService;
import com.stocka.backend.modules.auth.service.RefreshTokenService.IssuedRefreshToken;
import com.stocka.backend.modules.common.dto.AvailabilityResponse;
import com.stocka.backend.modules.security.service.JwtService;
import com.stocka.backend.modules.users.dto.UserResponseDto;
import com.stocka.backend.modules.users.entity.User;

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

    public AuthController(
            AuthenticationService authenticationService,
            PasswordResetService passwordResetService,
            EmailVerificationService emailVerificationService,
            JwtService jwtService,
            RefreshTokenService refreshTokenService,
            RefreshTokenCookieFactory refreshCookieFactory
    ) {
        this.authenticationService = authenticationService;
        this.passwordResetService = passwordResetService;
        this.emailVerificationService = emailVerificationService;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
        this.refreshCookieFactory = refreshCookieFactory;
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
    public ResponseEntity<LoginResponseDto> login(@Valid @RequestBody LoginUserDto loginUserDto) {
        User authenticatedUser = authenticationService.authenticate(loginUserDto);
        String jwtToken = jwtService.generateToken(authenticatedUser);
        IssuedRefreshToken refresh = refreshTokenService.issueForLogin(
                authenticatedUser, loginUserDto.isRememberMe());

        LoginResponseDto body = new LoginResponseDto()
                .setAccessToken(jwtToken)
                .setExpiresIn(jwtService.getExpirationTime())
                .setUser(UserResponseDto.from(authenticatedUser));
        ResponseCookie cookie = refreshCookieFactory.build(refresh.rawToken(), loginUserDto.isRememberMe());

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(body);
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

        User user = rotated.entity().getUser();
        String accessToken = jwtService.generateToken(user);
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
