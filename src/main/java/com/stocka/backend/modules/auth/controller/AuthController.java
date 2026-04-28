package com.stocka.backend.modules.auth.controller;

import org.springframework.http.HttpStatus;
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
import com.stocka.backend.modules.auth.dto.ResetPasswordRequestDto;
import com.stocka.backend.modules.auth.service.AuthenticationService;
import com.stocka.backend.modules.auth.service.PasswordResetService;
import com.stocka.backend.modules.common.dto.AvailabilityResponse;
import com.stocka.backend.modules.security.service.JwtService;
import com.stocka.backend.modules.users.entity.User;

@RestController
@RequestMapping("/auth")
public class AuthController {
    private final AuthenticationService authenticationService;
    private final PasswordResetService passwordResetService;
    private final JwtService jwtService;

    public AuthController(
            AuthenticationService authenticationService,
            PasswordResetService passwordResetService,
            JwtService jwtService
    ) {
        this.authenticationService = authenticationService;
        this.passwordResetService = passwordResetService;
        this.jwtService = jwtService;
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader("Authorization") String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Token no proporcionado");
        }
        authenticationService.logout(authHeader.substring(7));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/signup")
    public ResponseEntity<User> signup(@RequestBody RegisterUserDto registerUserDto) {
        User registeredUser = authenticationService.signup(registerUserDto);
        return ResponseEntity.ok(registeredUser);
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
    public ResponseEntity<LoginResponseDto> login(@RequestBody LoginUserDto loginUserDto) {
        User authenticatedUser = authenticationService.authenticate(loginUserDto);
        String jwtToken = jwtService.generateToken(authenticatedUser);

        LoginResponseDto loginResponse = new LoginResponseDto()
                .setToken(jwtToken)
                .setExpiresIn(jwtService.getExpirationTime())
                .setUser(authenticatedUser);

        return ResponseEntity.ok(loginResponse);
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<Void> forgotPassword(@RequestBody ForgotPasswordRequestDto dto) {
        passwordResetService.requestReset(dto.getEmail());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Void> resetPassword(@RequestBody ResetPasswordRequestDto dto) {
        passwordResetService.resetPassword(dto);
        return ResponseEntity.noContent().build();
    }
}
