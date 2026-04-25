package com.stocka.backend.modules.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.stocka.backend.modules.auth.dto.ResetPasswordRequestDto;
import com.stocka.backend.modules.auth.entity.PasswordResetToken;
import com.stocka.backend.modules.auth.repository.PasswordResetTokenRepository;
import com.stocka.backend.modules.notifications.email.EmailService;
import com.stocka.backend.modules.users.entity.User;
import com.stocka.backend.modules.users.repository.UserRepository;

@Service
public class PasswordResetService {

    private static final int TOKEN_BYTES = 32;
    private static final int MIN_PASSWORD_LENGTH = 8;

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final SecureRandom secureRandom = new SecureRandom();
    private final long ttlMinutes;
    private final String frontendBaseUrl;

    public PasswordResetService(
            UserRepository userRepository,
            PasswordResetTokenRepository tokenRepository,
            PasswordEncoder passwordEncoder,
            EmailService emailService,
            @Value("${app.password-reset.token-ttl-minutes:30}") long ttlMinutes,
            @Value("${app.frontend.base-url}") String frontendBaseUrl
    ) {
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
        this.ttlMinutes = ttlMinutes;
        this.frontendBaseUrl = frontendBaseUrl;
    }

    public void requestReset(String email) {
        if (email == null || email.isBlank()) {
            return;
        }

        Optional<User> maybeUser = userRepository.findByEmail(email);
        if (maybeUser.isEmpty()) {
            return;
        }

        User user = maybeUser.get();
        tokenRepository.deleteAllByUser(user);

        String rawToken = generateRawToken();
        LocalDateTime now = LocalDateTime.now();

        PasswordResetToken token = new PasswordResetToken()
                .setUser(user)
                .setTokenHash(hashToken(rawToken))
                .setExpiresAt(now.plusMinutes(ttlMinutes))
                .setCreatedAt(now);
        tokenRepository.save(token);

        String resetUrl = buildResetUrl(rawToken);
        emailService.sendPasswordResetEmail(user.getEmail(), user.getName(), resetUrl);
    }

    public void resetPassword(ResetPasswordRequestDto dto) {
        if (dto.getToken() == null || dto.getToken().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Token no proporcionado");
        }
        if (dto.getNewPassword() == null || dto.getNewPassword().length() < MIN_PASSWORD_LENGTH) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "La contraseña debe tener al menos " + MIN_PASSWORD_LENGTH + " caracteres"
            );
        }
        if (!dto.getNewPassword().equals(dto.getRepeatPassword())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Las contraseñas no coinciden");
        }

        PasswordResetToken token = tokenRepository.findByTokenHash(hashToken(dto.getToken()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Token inválido o expirado"));

        LocalDateTime now = LocalDateTime.now();
        if (token.getUsedAt() != null || token.getExpiresAt().isBefore(now)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Token inválido o expirado");
        }

        User user = token.getUser();
        user.setPassword(passwordEncoder.encode(dto.getNewPassword()));
        user.setPasswordChangedAt(now);
        userRepository.save(user);

        token.setUsedAt(now);
        tokenRepository.save(token);
    }

    private String generateRawToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible en la JVM", e);
        }
    }

    private String buildResetUrl(String rawToken) {
        String base = frontendBaseUrl.endsWith("/") ? frontendBaseUrl.substring(0, frontendBaseUrl.length() - 1) : frontendBaseUrl;
        return base + "/reset-password?token=" + rawToken;
    }
}
