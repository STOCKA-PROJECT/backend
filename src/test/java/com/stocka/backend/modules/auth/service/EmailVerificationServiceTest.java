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
import org.springframework.test.util.ReflectionTestUtils;

import com.stocka.backend.modules.auth.entity.EmailVerificationToken;
import com.stocka.backend.modules.auth.repository.EmailVerificationTokenRepository;
import com.stocka.backend.modules.common.error.ApiException;
import com.stocka.backend.modules.common.error.ErrorCodes;
import com.stocka.backend.modules.notifications.email.EmailService;
import com.stocka.backend.modules.users.entity.Language;
import com.stocka.backend.modules.users.entity.User;
import com.stocka.backend.modules.users.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("EmailVerificationService")
class EmailVerificationServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private EmailVerificationTokenRepository tokenRepository;
    @Mock
    private EmailService emailService;
    @Mock
    private com.stocka.backend.modules.security.audit.SecurityAuditService securityAuditService;

    private EmailVerificationService sut;

    private User existingUser;

    @BeforeEach
    void setUp() {
        sut = new EmailVerificationService(
                userRepository,
                tokenRepository,
                emailService,
                securityAuditService,
                1440L,
                "http://localhost:3002");
        existingUser = new User()
                .setId(42)
                .setName("Joan")
                .setLastName("Test")
                .setUsername("joantest")
                .setEmail("joan@test.com")
                .setPassword("hashed")
                .setEmailVerified(false)
                .setLanguage(Language.ES);
    }

    private static String sha256Hex(String raw) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
    }

    // -------------------------------------------------------------------------
    // sendVerificationEmail
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("sendVerificationEmail")
    class SendVerificationEmail {

        @Test
        @DisplayName("should delete previous tokens for the user before saving the new one")
        void should_deleteOldTokens_before_saving() {
            InOrder ordered = inOrder(tokenRepository);

            sut.sendVerificationEmail(existingUser);

            ordered.verify(tokenRepository).deleteAllByUser(existingUser);
            ordered.verify(tokenRepository).save(any(EmailVerificationToken.class));
        }

        @Test
        @DisplayName("should persist a hashed token (never the raw token) and the right metadata")
        void should_persistHashedToken_with_correctMetadata() {
            ArgumentCaptor<EmailVerificationToken> captor = ArgumentCaptor.forClass(EmailVerificationToken.class);

            LocalDateTime before = LocalDateTime.now();
            sut.sendVerificationEmail(existingUser);
            LocalDateTime after = LocalDateTime.now();

            verify(tokenRepository).save(captor.capture());
            EmailVerificationToken saved = captor.getValue();

            assertNotNull(saved.getTokenHash(), "token hash must be present");
            assertEquals(64, saved.getTokenHash().length(), "SHA-256 hex must be 64 chars");
            assertEquals(existingUser, saved.getUser());
            assertNull(saved.getUsedAt(), "newly created token must not be used");
            assertNotNull(saved.getCreatedAt());
            assertNotNull(saved.getExpiresAt());
            assertTrue(!saved.getExpiresAt().isBefore(before.plusMinutes(1440).minusSeconds(2))
                    && !saved.getExpiresAt().isAfter(after.plusMinutes(1440).plusSeconds(2)),
                    "expiresAt should be ~24h from now");
        }

        @Test
        @DisplayName("should respect the configured TTL when expiresAt is computed")
        void should_useConfiguredTtl_for_expiresAt() {
            sut = new EmailVerificationService(
                    userRepository, tokenRepository, emailService,
                    securityAuditService, 10L, "http://localhost:3002");
            ArgumentCaptor<EmailVerificationToken> captor = ArgumentCaptor.forClass(EmailVerificationToken.class);

            LocalDateTime before = LocalDateTime.now();
            sut.sendVerificationEmail(existingUser);
            LocalDateTime after = LocalDateTime.now();

            verify(tokenRepository).save(captor.capture());
            EmailVerificationToken saved = captor.getValue();
            assertTrue(!saved.getExpiresAt().isBefore(before.plusMinutes(10).minusSeconds(2))
                    && !saved.getExpiresAt().isAfter(after.plusMinutes(10).plusSeconds(2)));
        }

        @Test
        @DisplayName("should send the verification email with a frontend URL containing the raw token")
        void should_sendEmail_with_frontendUrlContainingRawToken() {
            sut.sendVerificationEmail(existingUser);

            ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
            verify(emailService).sendEmailVerification(eq(existingUser.getEmail()), eq(existingUser.getName()),
                    urlCaptor.capture(), eq(Language.ES));
            String url = urlCaptor.getValue();
            assertTrue(url.startsWith("http://localhost:3002/verificar-email?token="),
                    "url must point to frontend verify path: " + url);
            assertTrue(url.length() > "http://localhost:3002/verificar-email?token=".length(),
                    "url must include a non-empty token");
        }

        @Test
        @DisplayName("should generate distinct tokens on each invocation")
        void should_generateDistinctTokens_acrossCalls() {
            ArgumentCaptor<EmailVerificationToken> captor = ArgumentCaptor.forClass(EmailVerificationToken.class);

            sut.sendVerificationEmail(existingUser);
            sut.sendVerificationEmail(existingUser);

            verify(tokenRepository, times(2)).save(captor.capture());
            assertNotEquals(
                    captor.getAllValues().get(0).getTokenHash(),
                    captor.getAllValues().get(1).getTokenHash(),
                    "two consecutive tokens must not match");
        }

        @Test
        @DisplayName("should strip a trailing slash from the frontend base url before building the verify link")
        void should_stripTrailingSlash_from_frontendBaseUrl() {
            sut = new EmailVerificationService(
                    userRepository, tokenRepository, emailService,
                    securityAuditService, 1440L, "http://localhost:3002/");
            ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);

            sut.sendVerificationEmail(existingUser);

            verify(emailService).sendEmailVerification(anyString(), anyString(), urlCaptor.capture(),
                    any(Language.class));
            assertTrue(urlCaptor.getValue().startsWith("http://localhost:3002/verificar-email?token="),
                    "should not produce // before verificar-email: " + urlCaptor.getValue());
        }

        @Test
        @DisplayName("should pass the user's language ES to emailService")
        void should_passLanguageEs_toEmailService() {
            existingUser.setLanguage(Language.ES);
            ArgumentCaptor<Language> languageCaptor = ArgumentCaptor.forClass(Language.class);

            sut.sendVerificationEmail(existingUser);

            verify(emailService).sendEmailVerification(anyString(), anyString(), anyString(),
                    languageCaptor.capture());
            assertEquals(Language.ES, languageCaptor.getValue());
        }

        @Test
        @DisplayName("should pass the user's language EN to emailService")
        void should_passLanguageEn_toEmailService() {
            existingUser.setLanguage(Language.EN);
            ArgumentCaptor<Language> languageCaptor = ArgumentCaptor.forClass(Language.class);

            sut.sendVerificationEmail(existingUser);

            verify(emailService).sendEmailVerification(anyString(), anyString(), anyString(),
                    languageCaptor.capture());
            assertEquals(Language.EN, languageCaptor.getValue());
        }

        @Test
        @DisplayName("should pass the user's language CA to emailService")
        void should_passLanguageCa_toEmailService() {
            existingUser.setLanguage(Language.CA);
            ArgumentCaptor<Language> languageCaptor = ArgumentCaptor.forClass(Language.class);

            sut.sendVerificationEmail(existingUser);

            verify(emailService).sendEmailVerification(anyString(), anyString(), anyString(),
                    languageCaptor.capture());
            assertEquals(Language.CA, languageCaptor.getValue());
        }
    }

    // -------------------------------------------------------------------------
    // verify
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("verify")
    class Verify {

        private EmailVerificationToken validStoredTokenFor(String rawToken) throws Exception {
            return new EmailVerificationToken()
                    .setUser(existingUser)
                    .setTokenHash(sha256Hex(rawToken))
                    .setExpiresAt(LocalDateTime.now().plusMinutes(60))
                    .setCreatedAt(LocalDateTime.now().minusMinutes(1));
        }

        @Test
        @DisplayName("should throw 400 with code AUTH_VERIFICATION_TOKEN_INVALID when token is null")
        void should_throw400_when_tokenIsNull() {
            ApiException ex = assertThrows(ApiException.class, () -> sut.verify(null));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertEquals(ErrorCodes.AUTH_VERIFICATION_TOKEN_INVALID, ex.getCode());
            verifyNoInteractions(tokenRepository, userRepository);
        }

        @Test
        @DisplayName("should throw 400 when token is blank")
        void should_throw400_when_tokenIsBlank() {
            ApiException ex = assertThrows(ApiException.class, () -> sut.verify("   "));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertEquals(ErrorCodes.AUTH_VERIFICATION_TOKEN_INVALID, ex.getCode());
            verifyNoInteractions(tokenRepository, userRepository);
        }

        @Test
        @DisplayName("should throw 400 when token is not found in repository")
        void should_throw400_when_tokenNotFound() throws Exception {
            when(tokenRepository.findByTokenHash(sha256Hex("raw-token-xyz"))).thenReturn(Optional.empty());

            ApiException ex = assertThrows(ApiException.class, () -> sut.verify("raw-token-xyz"));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertEquals(ErrorCodes.AUTH_VERIFICATION_TOKEN_INVALID, ex.getCode());
            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("should throw 400 when token is already used")
        void should_throw400_when_tokenAlreadyUsed() throws Exception {
            EmailVerificationToken stored = validStoredTokenFor("raw-token-xyz")
                    .setUsedAt(LocalDateTime.now().minusMinutes(1));
            when(tokenRepository.findByTokenHash(sha256Hex("raw-token-xyz"))).thenReturn(Optional.of(stored));

            ApiException ex = assertThrows(ApiException.class, () -> sut.verify("raw-token-xyz"));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertEquals(ErrorCodes.AUTH_VERIFICATION_TOKEN_INVALID, ex.getCode());
            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("should throw 400 when token has expired")
        void should_throw400_when_tokenExpired() throws Exception {
            EmailVerificationToken stored = validStoredTokenFor("raw-token-xyz")
                    .setExpiresAt(LocalDateTime.now().minusSeconds(1));
            when(tokenRepository.findByTokenHash(sha256Hex("raw-token-xyz"))).thenReturn(Optional.of(stored));

            ApiException ex = assertThrows(ApiException.class, () -> sut.verify("raw-token-xyz"));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertEquals(ErrorCodes.AUTH_VERIFICATION_TOKEN_INVALID, ex.getCode());
            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("should set emailVerified=true and mark token used when token is valid")
        void should_setEmailVerifiedTrue_andMarkTokenUsed_when_validToken() throws Exception {
            EmailVerificationToken stored = validStoredTokenFor("raw-token-xyz");
            when(tokenRepository.findByTokenHash(sha256Hex("raw-token-xyz"))).thenReturn(Optional.of(stored));
            ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
            ArgumentCaptor<EmailVerificationToken> tokenCaptor = ArgumentCaptor.forClass(EmailVerificationToken.class);

            LocalDateTime before = LocalDateTime.now();
            sut.verify("raw-token-xyz");
            LocalDateTime after = LocalDateTime.now();

            verify(userRepository).save(userCaptor.capture());
            assertTrue(userCaptor.getValue().isEmailVerified(), "user must be flagged as verified");

            verify(tokenRepository).save(tokenCaptor.capture());
            LocalDateTime usedAt = tokenCaptor.getValue().getUsedAt();
            assertNotNull(usedAt);
            assertTrue(!usedAt.isBefore(before) && !usedAt.isAfter(after));
        }

        @Test
        @DisplayName("should save user before marking token as used (so a token-save failure cannot leave a verified user with an unburned token)")
        void should_saveUser_before_savingToken() throws Exception {
            EmailVerificationToken stored = validStoredTokenFor("raw-token-xyz");
            when(tokenRepository.findByTokenHash(sha256Hex("raw-token-xyz"))).thenReturn(Optional.of(stored));
            InOrder ordered = inOrder(userRepository, tokenRepository);

            sut.verify("raw-token-xyz");

            ordered.verify(userRepository).save(any(User.class));
            ordered.verify(tokenRepository).save(any(EmailVerificationToken.class));
        }

        @Test
        @DisplayName("should be idempotent (still burn token, no user.save) when user is already verified")
        void should_beIdempotent_when_userAlreadyVerified() throws Exception {
            existingUser.setEmailVerified(true);
            EmailVerificationToken stored = validStoredTokenFor("raw-token-xyz");
            when(tokenRepository.findByTokenHash(sha256Hex("raw-token-xyz"))).thenReturn(Optional.of(stored));
            ArgumentCaptor<EmailVerificationToken> tokenCaptor = ArgumentCaptor.forClass(EmailVerificationToken.class);

            sut.verify("raw-token-xyz");

            verify(userRepository, never()).save(any());
            verify(tokenRepository).save(tokenCaptor.capture());
            assertNotNull(tokenCaptor.getValue().getUsedAt(), "token must still be burned to avoid replay");
        }

        @Test
        @DisplayName("should not call userRepository.save when token validation fails")
        void should_notTouchUser_when_tokenInvalid() {
            when(tokenRepository.findByTokenHash(any())).thenReturn(Optional.empty());

            assertThrows(ApiException.class, () -> sut.verify("raw-token-xyz"));

            verify(userRepository, never()).save(any());
        }
    }

    // -------------------------------------------------------------------------
    // requestResend
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("requestResend")
    class RequestResend {

        @Test
        @DisplayName("should be a no-op when email is null (no repo or email calls)")
        void should_doNothing_when_emailIsNull() {
            sut.requestResend(null);

            verifyNoInteractions(userRepository, tokenRepository, emailService);
        }

        @Test
        @DisplayName("should be a no-op when email is blank")
        void should_doNothing_when_emailIsBlank() {
            sut.requestResend("   ");

            verifyNoInteractions(userRepository, tokenRepository, emailService);
        }

        @Test
        @DisplayName("should not send email or persist token when user does not exist (anti-enumeration)")
        void should_doNothing_when_userNotFound() {
            when(userRepository.findByEmail("ghost@test.com")).thenReturn(Optional.empty());

            sut.requestResend("ghost@test.com");

            verify(userRepository).findByEmail("ghost@test.com");
            verifyNoInteractions(tokenRepository, emailService);
        }

        @Test
        @DisplayName("should not send email when user is already verified (anti-enumeration)")
        void should_doNothing_when_userAlreadyVerified() {
            existingUser.setEmailVerified(true);
            when(userRepository.findByEmail(existingUser.getEmail())).thenReturn(Optional.of(existingUser));

            sut.requestResend(existingUser.getEmail());

            verify(userRepository).findByEmail(existingUser.getEmail());
            verifyNoInteractions(tokenRepository, emailService);
        }

        @Test
        @DisplayName("should send email and delete previous tokens when user exists and is unverified")
        void should_sendEmail_andDeleteOldTokens_when_userExistsAndUnverified() {
            when(userRepository.findByEmail(existingUser.getEmail())).thenReturn(Optional.of(existingUser));

            sut.requestResend(existingUser.getEmail());

            verify(tokenRepository).deleteAllByUser(existingUser);
            verify(tokenRepository).save(any(EmailVerificationToken.class));
            verify(emailService).sendEmailVerification(eq(existingUser.getEmail()),
                    eq(existingUser.getName()), anyString(), eq(Language.ES));
        }
    }

    // -------------------------------------------------------------------------
    // internal contract
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("internal contract")
    class InternalContract {

        @Test
        @DisplayName("should use a 64-character SHA-256 hex hash for stored tokens")
        void should_use64CharHash() {
            ArgumentCaptor<EmailVerificationToken> captor = ArgumentCaptor.forClass(EmailVerificationToken.class);

            sut.sendVerificationEmail(existingUser);

            verify(tokenRepository).save(captor.capture());
            assertEquals(64, captor.getValue().getTokenHash().length());
        }

        @Test
        @DisplayName("instance is wired with the constructor TTL value (sanity)")
        void ttlConstant_isHonored() {
            assertEquals(1440L, ReflectionTestUtils.getField(sut, "ttlMinutes"));
        }
    }
}
