package com.stocka.backend.modules.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.InOrder;
import org.mockito.Mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import com.stocka.backend.modules.auth.dto.ResetPasswordRequestDto;
import com.stocka.backend.modules.auth.entity.PasswordResetToken;
import com.stocka.backend.modules.auth.repository.PasswordResetTokenRepository;
import com.stocka.backend.modules.notifications.email.EmailService;
import com.stocka.backend.modules.users.entity.Language;
import com.stocka.backend.modules.users.entity.User;
import com.stocka.backend.modules.users.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("PasswordResetService")
class PasswordResetServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordResetTokenRepository tokenRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private EmailService emailService;
    @Mock
    private RefreshTokenService refreshTokenService;
    @Mock
    private com.stocka.backend.modules.security.audit.SecurityAuditService securityAuditService;

    private PasswordResetService sut;

    private User existingUser;

    @BeforeEach
    void setUp() {
        sut = new PasswordResetService(
                userRepository,
                tokenRepository,
                passwordEncoder,
                emailService,
                refreshTokenService,
                securityAuditService,
                30L,
                "http://localhost:3002");
        existingUser = new User()
                .setId(42)
                .setName("Joan")
                .setLastName("Test")
                .setUsername("joantest")
                .setEmail("joan@test.com")
                .setPassword("oldHashed");
    }

    private static String sha256Hex(String raw) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
    }

    // -------------------------------------------------------------------------
    // requestReset
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("requestReset")
    class RequestReset {

        @Test
        @DisplayName("should be a no-op when email is null (no repo or email calls)")
        void should_doNothing_when_emailIsNull() {
            sut.requestReset(null);

            verifyNoInteractions(userRepository, tokenRepository, emailService);
        }

        @Test
        @DisplayName("should be a no-op when email is blank (no repo or email calls)")
        void should_doNothing_when_emailIsBlank() {
            sut.requestReset("   ");

            verifyNoInteractions(userRepository, tokenRepository, emailService);
        }

        @Test
        @DisplayName("should not send email or persist token when user does not exist (anti-enumeration)")
        void should_doNothing_when_userNotFound() {
            when(userRepository.findByEmail("nobody@test.com")).thenReturn(Optional.empty());

            sut.requestReset("nobody@test.com");

            verify(userRepository).findByEmail("nobody@test.com");
            verifyNoInteractions(tokenRepository, emailService);
        }

        @Test
        @DisplayName("should delete previous tokens for the user before saving the new one")
        void should_deleteOldTokens_before_saving() {
            when(userRepository.findByEmail(existingUser.getEmail())).thenReturn(Optional.of(existingUser));
            InOrder ordered = inOrder(tokenRepository);

            sut.requestReset(existingUser.getEmail());

            ordered.verify(tokenRepository).deleteAllByUser(existingUser);
            ordered.verify(tokenRepository).save(any(PasswordResetToken.class));
        }

        @Test
        @DisplayName("should persist a hashed token (never the raw token) and the right metadata")
        void should_persistHashedToken_with_correctMetadata() {
            when(userRepository.findByEmail(existingUser.getEmail())).thenReturn(Optional.of(existingUser));
            ArgumentCaptor<PasswordResetToken> captor = ArgumentCaptor.forClass(PasswordResetToken.class);

            LocalDateTime before = LocalDateTime.now();
            sut.requestReset(existingUser.getEmail());
            LocalDateTime after = LocalDateTime.now();

            verify(tokenRepository).save(captor.capture());
            PasswordResetToken saved = captor.getValue();

            assertNotNull(saved.getTokenHash(), "token hash must be present");
            assertEquals(64, saved.getTokenHash().length(), "SHA-256 hex must be 64 chars");
            assertEquals(existingUser, saved.getUser());
            assertNull(saved.getUsedAt(), "newly created token must not be used");
            assertNotNull(saved.getCreatedAt());
            assertNotNull(saved.getExpiresAt());
            assertTrue(!saved.getExpiresAt().isBefore(before.plusMinutes(30).minusSeconds(2))
                    && !saved.getExpiresAt().isAfter(after.plusMinutes(30).plusSeconds(2)),
                    "expiresAt should be ~30 minutes from now");
        }

        @Test
        @DisplayName("should send the reset email with a frontend URL containing the raw token")
        void should_sendEmail_with_frontendUrlContainingRawToken() {
            when(userRepository.findByEmail(existingUser.getEmail())).thenReturn(Optional.of(existingUser));

            sut.requestReset(existingUser.getEmail());

            ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
            verify(emailService).sendPasswordResetEmail(eq(existingUser.getEmail()), eq(existingUser.getName()),
                    urlCaptor.capture(), eq(Language.ES));
            String url = urlCaptor.getValue();
            assertTrue(url.startsWith("http://localhost:3002/restablecer-password?token="),
                    "url must point to frontend reset path: " + url);
            assertTrue(url.length() > "http://localhost:3002/restablecer-password?token=".length(),
                    "url must include a non-empty token");
        }

        @Test
        @DisplayName("should generate distinct tokens on each invocation")
        void should_generateDistinctTokens_acrossCalls() {
            when(userRepository.findByEmail(existingUser.getEmail())).thenReturn(Optional.of(existingUser));
            ArgumentCaptor<PasswordResetToken> captor = ArgumentCaptor.forClass(PasswordResetToken.class);

            sut.requestReset(existingUser.getEmail());
            sut.requestReset(existingUser.getEmail());

            verify(tokenRepository, times(2)).save(captor.capture());
            assertNotEquals(
                    captor.getAllValues().get(0).getTokenHash(),
                    captor.getAllValues().get(1).getTokenHash(),
                    "two consecutive tokens must not match");
        }

        @Test
        @DisplayName("should strip a trailing slash from the frontend base url before building the reset link")
        void should_stripTrailingSlash_from_frontendBaseUrl() {
            sut = new PasswordResetService(
                    userRepository, tokenRepository, passwordEncoder, emailService,
                    refreshTokenService, securityAuditService, 30L, "http://localhost:3002/");
            when(userRepository.findByEmail(existingUser.getEmail())).thenReturn(Optional.of(existingUser));
            ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);

            sut.requestReset(existingUser.getEmail());

            verify(emailService).sendPasswordResetEmail(anyString(), anyString(), urlCaptor.capture(),
                    any(Language.class));
            assertTrue(urlCaptor.getValue().startsWith("http://localhost:3002/restablecer-password?token="),
                    "should not produce // before restablecer-password: " + urlCaptor.getValue());
        }

        @Test
        @DisplayName("should respect the configured TTL when expiresAt is computed")
        void should_useConfiguredTtl_for_expiresAt() {
            sut = new PasswordResetService(
                    userRepository, tokenRepository, passwordEncoder, emailService,
                    refreshTokenService, securityAuditService, 5L, "http://localhost:3002");
            when(userRepository.findByEmail(existingUser.getEmail())).thenReturn(Optional.of(existingUser));
            ArgumentCaptor<PasswordResetToken> captor = ArgumentCaptor.forClass(PasswordResetToken.class);

            LocalDateTime before = LocalDateTime.now();
            sut.requestReset(existingUser.getEmail());
            LocalDateTime after = LocalDateTime.now();

            verify(tokenRepository).save(captor.capture());
            PasswordResetToken saved = captor.getValue();
            assertTrue(!saved.getExpiresAt().isBefore(before.plusMinutes(5).minusSeconds(2))
                    && !saved.getExpiresAt().isAfter(after.plusMinutes(5).plusSeconds(2)));
        }

        @Test
        @DisplayName("should pass the user's language ES to emailService")
        void should_passLanguageEs_toEmailService() {
            existingUser.setLanguage(Language.ES);
            when(userRepository.findByEmail(existingUser.getEmail())).thenReturn(Optional.of(existingUser));
            ArgumentCaptor<Language> languageCaptor = ArgumentCaptor.forClass(Language.class);

            sut.requestReset(existingUser.getEmail());

            verify(emailService).sendPasswordResetEmail(anyString(), anyString(), anyString(),
                    languageCaptor.capture());
            assertEquals(Language.ES, languageCaptor.getValue());
        }

        @Test
        @DisplayName("should pass the user's language EN to emailService")
        void should_passLanguageEn_toEmailService() {
            existingUser.setLanguage(Language.EN);
            when(userRepository.findByEmail(existingUser.getEmail())).thenReturn(Optional.of(existingUser));
            ArgumentCaptor<Language> languageCaptor = ArgumentCaptor.forClass(Language.class);

            sut.requestReset(existingUser.getEmail());

            verify(emailService).sendPasswordResetEmail(anyString(), anyString(), anyString(),
                    languageCaptor.capture());
            assertEquals(Language.EN, languageCaptor.getValue());
        }

        @Test
        @DisplayName("should pass the user's language CA to emailService")
        void should_passLanguageCa_toEmailService() {
            existingUser.setLanguage(Language.CA);
            when(userRepository.findByEmail(existingUser.getEmail())).thenReturn(Optional.of(existingUser));
            ArgumentCaptor<Language> languageCaptor = ArgumentCaptor.forClass(Language.class);

            sut.requestReset(existingUser.getEmail());

            verify(emailService).sendPasswordResetEmail(anyString(), anyString(), anyString(),
                    languageCaptor.capture());
            assertEquals(Language.CA, languageCaptor.getValue());
        }
    }

    // -------------------------------------------------------------------------
    // resetPassword
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("resetPassword")
    class ResetPassword {

        private ResetPasswordRequestDto validDto() {
            return new ResetPasswordRequestDto()
                    .setToken("raw-token-abc")
                    .setNewPassword("newPassword123")
                    .setRepeatPassword("newPassword123");
        }

        private PasswordResetToken validStoredTokenFor(String rawToken) throws Exception {
            return new PasswordResetToken()
                    .setUser(existingUser)
                    .setTokenHash(sha256Hex(rawToken))
                    .setExpiresAt(LocalDateTime.now().plusMinutes(10))
                    .setCreatedAt(LocalDateTime.now().minusMinutes(1));
        }

        @Test
        @DisplayName("should throw 400 when token is null")
        void should_throw400_when_tokenIsNull() {
            ResetPasswordRequestDto dto = validDto().setToken(null);

            ResponseStatusException ex = assertThrows(
                    ResponseStatusException.class,
                    () -> sut.resetPassword(dto));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
            verifyNoInteractions(tokenRepository, userRepository, passwordEncoder);
        }

        @Test
        @DisplayName("should throw 400 when token is blank")
        void should_throw400_when_tokenIsBlank() {
            ResetPasswordRequestDto dto = validDto().setToken("   ");

            ResponseStatusException ex = assertThrows(
                    ResponseStatusException.class,
                    () -> sut.resetPassword(dto));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
            verifyNoInteractions(tokenRepository, userRepository, passwordEncoder);
        }

        @Test
        @DisplayName("should throw 400 when newPassword is null")
        void should_throw400_when_newPasswordNull() {
            ResetPasswordRequestDto dto = validDto().setNewPassword(null);

            ResponseStatusException ex = assertThrows(
                    ResponseStatusException.class,
                    () -> sut.resetPassword(dto));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
            verifyNoInteractions(tokenRepository, userRepository, passwordEncoder);
        }

        @Test
        @DisplayName("should throw 400 when newPassword is shorter than 8 chars")
        void should_throw400_when_newPasswordTooShort() {
            ResetPasswordRequestDto dto = validDto().setNewPassword("short").setRepeatPassword("short");

            ResponseStatusException ex = assertThrows(
                    ResponseStatusException.class,
                    () -> sut.resetPassword(dto));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
            verifyNoInteractions(tokenRepository, userRepository, passwordEncoder);
        }

        @Test
        @DisplayName("should throw 400 when newPassword and repeatPassword do not match")
        void should_throw400_when_passwordsDoNotMatch() {
            ResetPasswordRequestDto dto = validDto().setRepeatPassword("differentPassword");

            ResponseStatusException ex = assertThrows(
                    ResponseStatusException.class,
                    () -> sut.resetPassword(dto));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
            verifyNoInteractions(tokenRepository, userRepository, passwordEncoder);
        }

        @Test
        @DisplayName("should throw 400 when token is not found in repository")
        void should_throw400_when_tokenNotFound() throws Exception {
            ResetPasswordRequestDto dto = validDto();
            when(tokenRepository.findByTokenHash(sha256Hex("raw-token-abc"))).thenReturn(Optional.empty());

            ResponseStatusException ex = assertThrows(
                    ResponseStatusException.class,
                    () -> sut.resetPassword(dto));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
            verify(userRepository, never()).save(any());
            verify(passwordEncoder, never()).encode(any());
        }

        @Test
        @DisplayName("should throw 400 when token is already used")
        void should_throw400_when_tokenAlreadyUsed() throws Exception {
            PasswordResetToken stored = validStoredTokenFor("raw-token-abc")
                    .setUsedAt(LocalDateTime.now().minusMinutes(1));
            when(tokenRepository.findByTokenHash(sha256Hex("raw-token-abc"))).thenReturn(Optional.of(stored));

            ResponseStatusException ex = assertThrows(
                    ResponseStatusException.class,
                    () -> sut.resetPassword(validDto()));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("should throw 400 when token has expired")
        void should_throw400_when_tokenExpired() throws Exception {
            PasswordResetToken stored = validStoredTokenFor("raw-token-abc")
                    .setExpiresAt(LocalDateTime.now().minusSeconds(1));
            when(tokenRepository.findByTokenHash(sha256Hex("raw-token-abc"))).thenReturn(Optional.of(stored));

            ResponseStatusException ex = assertThrows(
                    ResponseStatusException.class,
                    () -> sut.resetPassword(validDto()));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("should hash the new password (never store it raw) when token is valid")
        void should_persistHashedPassword_when_validToken() throws Exception {
            PasswordResetToken stored = validStoredTokenFor("raw-token-abc");
            when(tokenRepository.findByTokenHash(sha256Hex("raw-token-abc"))).thenReturn(Optional.of(stored));
            when(passwordEncoder.encode("newPassword123")).thenReturn("encoded_value");
            ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);

            sut.resetPassword(validDto());

            verify(userRepository).save(userCaptor.capture());
            assertEquals("encoded_value", userCaptor.getValue().getPassword());
            assertNotEquals("newPassword123", userCaptor.getValue().getPassword());
        }

        @Test
        @DisplayName("should set passwordChangedAt to now when token is valid")
        void should_setPasswordChangedAt_when_validToken() throws Exception {
            PasswordResetToken stored = validStoredTokenFor("raw-token-abc");
            when(tokenRepository.findByTokenHash(sha256Hex("raw-token-abc"))).thenReturn(Optional.of(stored));
            when(passwordEncoder.encode(any())).thenReturn("encoded");
            ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);

            LocalDateTime before = LocalDateTime.now();
            sut.resetPassword(validDto());
            LocalDateTime after = LocalDateTime.now();

            verify(userRepository).save(userCaptor.capture());
            LocalDateTime stamp = userCaptor.getValue().getPasswordChangedAt();
            assertNotNull(stamp);
            assertTrue(!stamp.isBefore(before) && !stamp.isAfter(after));
        }

        @Test
        @DisplayName("should mark the token as used when reset succeeds")
        void should_markTokenUsed_when_resetSucceeds() throws Exception {
            PasswordResetToken stored = validStoredTokenFor("raw-token-abc");
            when(tokenRepository.findByTokenHash(sha256Hex("raw-token-abc"))).thenReturn(Optional.of(stored));
            when(passwordEncoder.encode(any())).thenReturn("encoded");
            ArgumentCaptor<PasswordResetToken> tokenCaptor = ArgumentCaptor.forClass(PasswordResetToken.class);

            LocalDateTime before = LocalDateTime.now();
            sut.resetPassword(validDto());
            LocalDateTime after = LocalDateTime.now();

            verify(tokenRepository).save(tokenCaptor.capture());
            LocalDateTime usedAt = tokenCaptor.getValue().getUsedAt();
            assertNotNull(usedAt);
            assertTrue(!usedAt.isBefore(before) && !usedAt.isAfter(after));
        }

        @Test
        @DisplayName("should save user before marking token as used (so a token-save failure cannot leave a usable token with stale password)")
        void should_saveUser_before_savingToken() throws Exception {
            PasswordResetToken stored = validStoredTokenFor("raw-token-abc");
            when(tokenRepository.findByTokenHash(sha256Hex("raw-token-abc"))).thenReturn(Optional.of(stored));
            when(passwordEncoder.encode(any())).thenReturn("encoded");
            InOrder ordered = inOrder(userRepository, tokenRepository);

            sut.resetPassword(validDto());

            ordered.verify(userRepository).save(any(User.class));
            ordered.verify(tokenRepository).save(any(PasswordResetToken.class));
        }

        @Test
        @DisplayName("should not call userRepository.save or passwordEncoder.encode when token validation fails")
        void should_notTouchUser_when_tokenInvalid() throws Exception {
            when(tokenRepository.findByTokenHash(any())).thenReturn(Optional.empty());

            assertThrows(ResponseStatusException.class, () -> sut.resetPassword(validDto()));

            verify(userRepository, never()).save(any());
            verify(passwordEncoder, never()).encode(any());
        }
    }

    // -------------------------------------------------------------------------
    // SecureRandom + min-password sanity (constants kept private; verify behavior)
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("internal contract")
    class InternalContract {

        @Test
        @DisplayName("should use a 64-character SHA-256 hex hash for stored tokens")
        void should_use64CharHash() {
            when(userRepository.findByEmail(existingUser.getEmail())).thenReturn(Optional.of(existingUser));
            ArgumentCaptor<PasswordResetToken> captor = ArgumentCaptor.forClass(PasswordResetToken.class);

            sut.requestReset(existingUser.getEmail());

            verify(tokenRepository).save(captor.capture());
            assertEquals(64, captor.getValue().getTokenHash().length());
        }

        @Test
        @DisplayName("instance is wired with the constructor TTL value (sanity)")
        void ttlConstant_isHonored() {
            assertEquals(30L, ReflectionTestUtils.getField(sut, "ttlMinutes"));
        }
    }
}
