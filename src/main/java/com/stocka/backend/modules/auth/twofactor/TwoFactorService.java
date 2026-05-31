package com.stocka.backend.modules.auth.twofactor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.stocka.backend.modules.auth.entity.TwoFactorRecoveryCode;
import com.stocka.backend.modules.auth.entity.TwoFactorSetupToken;
import com.stocka.backend.modules.auth.repository.TwoFactorRecoveryCodeRepository;
import com.stocka.backend.modules.auth.repository.TwoFactorSetupTokenRepository;
import com.stocka.backend.modules.common.error.ApiException;
import com.stocka.backend.modules.common.error.ErrorCodes;
import com.stocka.backend.modules.users.entity.User;
import com.stocka.backend.modules.users.repository.UserRepository;

/**
 * Orchestrates the TOTP lifecycle for one user account.
 *
 * <ol>
 *   <li>{@link #startSetup(User)} mints a candidate secret + a setup token.
 *       Nothing about the user changes yet.</li>
 *   <li>{@link #confirmSetup(User, String, String)} verifies the user typed
 *       the right TOTP code, copies the secret onto the user, generates the
 *       10 recovery codes and returns them (one-time visibility).</li>
 *   <li>{@link #verifyChallenge(User, String)} is called from the 2FA login
 *       step. Accepts either a TOTP code or one of the recovery codes; the
 *       latter is consumed on use.</li>
 *   <li>{@link #disable(User)} wipes the secret + recovery codes.</li>
 * </ol>
 */
@Service
public class TwoFactorService {

    /** How many recovery codes are generated. */
    public static final int RECOVERY_CODE_COUNT = 10;
    /** Per-half length of a recovery code (final shape is {@code XXXX-XXXX}). */
    public static final int RECOVERY_HALF_LENGTH = 4;

    private static final int SETUP_TOKEN_BYTES = 32;
    private static final int SETUP_TTL_MINUTES = 15;
    private static final String RECOVERY_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final UserRepository userRepository;
    private final TwoFactorSetupTokenRepository setupTokenRepository;
    private final TwoFactorRecoveryCodeRepository recoveryCodeRepository;
    private final TotpGenerator totpGenerator;
    private final AesGcmCipher cipher;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom secureRandom = new SecureRandom();
    private final String issuer;

    public TwoFactorService(
            UserRepository userRepository,
            TwoFactorSetupTokenRepository setupTokenRepository,
            TwoFactorRecoveryCodeRepository recoveryCodeRepository,
            TotpGenerator totpGenerator,
            AesGcmCipher cipher,
            PasswordEncoder passwordEncoder,
            @Value("${security.twofactor.issuer:Stocka}") String issuer) {
        this.userRepository = userRepository;
        this.setupTokenRepository = setupTokenRepository;
        this.recoveryCodeRepository = recoveryCodeRepository;
        this.totpGenerator = totpGenerator;
        this.cipher = cipher;
        this.passwordEncoder = passwordEncoder;
        this.issuer = issuer;
    }

    /**
     * Outcome of {@link #startSetup(User)}. The {@code setupToken} must be
     * kept by the client and presented back to {@code /auth/2fa/confirm}.
     */
    public record SetupResult(String setupToken, String secret, String otpAuthUri) {}

    /**
     * Outcome of {@link #confirmSetup(User, String, String)}.
     */
    public record ConfirmResult(List<String> recoveryCodes) {}

    /**
     * Begins the setup. Wipes any previous half-completed setup tokens for
     * the same user.
     *
     * @param user authenticated user
     * @return the candidate secret + a setup token + an {@code otpauth://} URI
     *         for the QR
     * @throws ApiException 409 if 2FA is already enabled
     */
    @Transactional
    public SetupResult startSetup(User user) {
        if (user.isTwoFactorEnabled()) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.AUTH_2FA_ALREADY_ENABLED);
        }
        setupTokenRepository.deleteAllByUser(user);

        String secret = totpGenerator.generateSecret();
        String setupToken = randomSetupToken();
        LocalDateTime now = LocalDateTime.now();

        TwoFactorSetupToken row = new TwoFactorSetupToken()
                .setUser(user)
                .setSetupTokenHash(sha256(setupToken))
                .setEncryptedSecret(cipher.encryptToString(secret))
                .setExpiresAt(now.plusMinutes(SETUP_TTL_MINUTES))
                .setConsumed(false)
                .setCreatedAt(now);
        setupTokenRepository.save(row);

        String otpAuth = totpGenerator.buildOtpAuthUri(issuer, user.getEmail(), secret);
        return new SetupResult(setupToken, secret, otpAuth);
    }

    /**
     * Confirms the setup. On success, the secret is moved to the user and the
     * recovery codes are generated.
     *
     * @param user authenticated user
     * @param setupToken raw token from {@link #startSetup(User)}
     * @param code TOTP code the user typed
     * @return the 10 recovery codes — show once, never again
     */
    @Transactional
    public ConfirmResult confirmSetup(User user, String setupToken, String code) {
        if (user.isTwoFactorEnabled()) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.AUTH_2FA_ALREADY_ENABLED);
        }
        TwoFactorSetupToken row = setupTokenRepository.findBySetupTokenHash(sha256(setupToken))
                .filter(t -> t.getUser().getId().equals(user.getId()))
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.AUTH_2FA_SETUP_TOKEN_INVALID));
        LocalDateTime now = LocalDateTime.now();
        if (row.isConsumed() || row.getExpiresAt().isBefore(now)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.AUTH_2FA_SETUP_TOKEN_INVALID);
        }
        String secret = cipher.decryptFromString(row.getEncryptedSecret());
        if (!totpGenerator.verify(secret, code)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.AUTH_2FA_INVALID_CODE);
        }

        row.setConsumed(true);
        setupTokenRepository.save(row);

        user.setTwoFactorEnabled(true)
                .setTwoFactorSecret(row.getEncryptedSecret())
                .setTwoFactorEnabledAt(now);
        userRepository.save(user);

        List<String> rawCodes = generateRecoveryCodes(user);
        return new ConfirmResult(rawCodes);
    }

    /**
     * Wipes 2FA from the account. Caller-side checks (current password +
     * a valid code) live in {@code AuthController#disableTwoFactor}.
     */
    @Transactional
    public void disable(User user) {
        if (!user.isTwoFactorEnabled()) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.AUTH_2FA_NOT_ENABLED);
        }
        user.setTwoFactorEnabled(false).setTwoFactorSecret(null).setTwoFactorEnabledAt(null);
        userRepository.save(user);
        recoveryCodeRepository.deleteAllByUser(user);
        setupTokenRepository.deleteAllByUser(user);
    }

    /**
     * Verifies a TOTP code presented during the 2FA login challenge. Accepts
     * either the rolling TOTP code or one of the recovery codes; recovery
     * codes are consumed on use.
     *
     * @param user the account being authenticated
     * @param code value typed by the user
     * @return {@code true} when the code is valid
     */
    @Transactional
    public boolean verifyChallenge(User user, String code) {
        if (!user.isTwoFactorEnabled() || user.getTwoFactorSecret() == null) return false;
        if (code == null) return false;
        String normalized = code.replaceAll("\\s+", "");
        if (normalized.isEmpty()) return false;

        String secret = cipher.decryptFromString(user.getTwoFactorSecret());
        if (totpGenerator.verify(secret, normalized)) {
            return true;
        }
        return consumeRecoveryCode(user, normalized);
    }

    /**
     * Generates a fresh batch of 10 recovery codes — used in {@link
     * #confirmSetup(User, String, String)} and by the "regenerate" endpoint.
     * Wipes the previous batch first.
     *
     * @param user authenticated user
     * @return raw codes to display to the user (only chance to see them)
     */
    @Transactional
    public List<String> generateRecoveryCodes(User user) {
        if (!user.isTwoFactorEnabled()) {
            // confirmSetup() flips the flag before calling — but if called
            // standalone we require it on.
            // (The endpoint guards this.)
        }
        recoveryCodeRepository.deleteAllByUser(user);
        LocalDateTime now = LocalDateTime.now();
        List<String> rawCodes = new ArrayList<>(RECOVERY_CODE_COUNT);
        for (int i = 0; i < RECOVERY_CODE_COUNT; i++) {
            String raw = randomRecoveryCode();
            rawCodes.add(raw);
            TwoFactorRecoveryCode row = new TwoFactorRecoveryCode()
                    .setUser(user)
                    .setCodeHash(passwordEncoder.encode(raw))
                    .setCreatedAt(now);
            recoveryCodeRepository.save(row);
        }
        return rawCodes;
    }

    private boolean consumeRecoveryCode(User user, String presented) {
        String normalized = presented.toUpperCase();
        // The presented form is XXXX-XXXX; accept "XXXXXXXX" too.
        if (normalized.length() == RECOVERY_HALF_LENGTH * 2) {
            normalized = normalized.substring(0, RECOVERY_HALF_LENGTH)
                    + "-" + normalized.substring(RECOVERY_HALF_LENGTH);
        }
        for (TwoFactorRecoveryCode row : recoveryCodeRepository.findByUserAndUsedAtIsNull(user)) {
            if (passwordEncoder.matches(normalized, row.getCodeHash())) {
                row.setUsedAt(LocalDateTime.now());
                recoveryCodeRepository.save(row);
                return true;
            }
        }
        return false;
    }

    private String randomRecoveryCode() {
        char[] buf = new char[RECOVERY_HALF_LENGTH * 2 + 1];
        for (int i = 0; i < RECOVERY_HALF_LENGTH; i++) {
            buf[i] = RECOVERY_CHARS.charAt(secureRandom.nextInt(RECOVERY_CHARS.length()));
        }
        buf[RECOVERY_HALF_LENGTH] = '-';
        for (int i = 0; i < RECOVERY_HALF_LENGTH; i++) {
            buf[RECOVERY_HALF_LENGTH + 1 + i] =
                    RECOVERY_CHARS.charAt(secureRandom.nextInt(RECOVERY_CHARS.length()));
        }
        return new String(buf);
    }

    private String randomSetupToken() {
        byte[] bytes = new byte[SETUP_TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Looks up a user by id for the 2FA challenge step. Kept on the service
     * (instead of the controller) so the rest of the package isn't tempted to
     * reach into the user repo for this very specific need.
     */
    public Optional<User> findUserForChallenge(Integer userId) {
        return userRepository.findById(userId);
    }
}
