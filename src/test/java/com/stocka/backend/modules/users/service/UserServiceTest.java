package com.stocka.backend.modules.users.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import com.stocka.backend.modules.auth.repository.EmailVerificationTokenRepository;
import com.stocka.backend.modules.auth.repository.PasswordResetTokenRepository;
import com.stocka.backend.modules.common.dto.AvailabilityResponse;
import com.stocka.backend.modules.common.dto.AvailabilityResponse.Reason;
import com.stocka.backend.modules.common.error.ApiException;
import com.stocka.backend.modules.common.error.ErrorCodes;
import com.stocka.backend.modules.notifications.preferences.service.NotificationPreferenceService;
import com.stocka.backend.modules.organizations.repository.OrganizationInvitationRepository;
import com.stocka.backend.modules.organizations.repository.OrganizationMemberRepository;
import com.stocka.backend.modules.users.dto.ChangePasswordDto;
import com.stocka.backend.modules.users.dto.UpdateUserProfileDto;
import com.stocka.backend.modules.users.entity.Language;
import com.stocka.backend.modules.users.entity.User;
import com.stocka.backend.modules.users.entity.UserUsernameHistory;
import com.stocka.backend.modules.users.repository.UserRepository;
import com.stocka.backend.modules.users.repository.UserUsernameHistoryRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserService")
class UserServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private UserUsernameHistoryRepository usernameHistoryRepository;
    @Mock private OrganizationMemberRepository memberRepository;
    @Mock private OrganizationInvitationRepository invitationRepository;
    @Mock private EmailVerificationTokenRepository emailVerificationTokenRepository;
    @Mock private PasswordResetTokenRepository passwordResetTokenRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private NotificationPreferenceService notificationPreferenceService;
    @Mock private com.stocka.backend.modules.auth.service.RefreshTokenService refreshTokenService;

    @InjectMocks
    private UserService sut;

    private User actor;

    @BeforeEach
    void setUp() {
        actor = new User()
                .setId(1)
                .setName("Joan")
                .setLastName("Test")
                .setUsername("joantest")
                .setEmail("joan@test.com")
                .setPassword("hashed")
                .setEmailVerified(true);
    }

    @Nested
    @DisplayName("allUsers")
    class AllUsers {

        @Test
        @DisplayName("should return all users from the repository")
        void should_returnAllUsersFromRepository() {
            User a = new User().setId(1);
            User b = new User().setId(2);
            when(userRepository.findAll()).thenReturn(List.of(a, b));

            List<User> result = sut.allUsers();

            assertEquals(2, result.size());
            assertTrue(result.contains(a));
            assertTrue(result.contains(b));
        }

        @Test
        @DisplayName("should return empty list when no users exist")
        void should_returnEmptyList_when_noUsers() {
            when(userRepository.findAll()).thenReturn(List.of());

            List<User> result = sut.allUsers();

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("softDeleteCurrentUser")
    class SoftDeleteCurrentUser {

        @Test
        @DisplayName("should set deletedAt, clear username history and release the username")
        void should_setDeletedAtAndSave() {
            sut.softDeleteCurrentUser(actor);

            assertNotNull(actor.getDeletedAt());
            verify(usernameHistoryRepository).deleteByUser(actor);
            // Active username is rewritten to a marker that fails USERNAME_PATTERN so it cannot
            // collide with a real username; the row is preserved for audit.
            assertTrue(actor.getUsernameValue().startsWith("__deleted_1_"));
            assertTrue(actor.getUsernameValue().endsWith("__"));
            assertFalse("joantest".equals(actor.getUsernameValue()));
            verify(userRepository).save(actor);
        }

        @Test
        @DisplayName("should overwrite deletedAt when called twice (idempotent)")
        void should_beIdempotent_when_calledTwice() {
            sut.softDeleteCurrentUser(actor);
            var first = actor.getDeletedAt();

            sut.softDeleteCurrentUser(actor);
            var second = actor.getDeletedAt();

            assertNotNull(first);
            assertNotNull(second);
            // second timestamp is >= first; we don't assert strict ordering due to clock granularity.
            verify(userRepository, org.mockito.Mockito.times(2)).save(actor);
        }

        @Test
        @DisplayName("should soft-delete invitations originated by this user and hard-delete auth tokens")
        void should_cascadeToInvitationsAndTokens() {
            com.stocka.backend.modules.organizations.entity.OrganizationInvitation inv =
                    new com.stocka.backend.modules.organizations.entity.OrganizationInvitation().setId(99);
            when(invitationRepository.findByInvitedBy(actor)).thenReturn(List.of(inv));

            sut.softDeleteCurrentUser(actor);

            // OrganizationInvitation.invitedBy FK is EAGER + non-nullable; leaving the row alive
            // after the inviter is gone would later blow up with ObjectNotFoundException.
            assertNotNull(inv.getDeletedAt());
            verify(invitationRepository).save(inv);
            // Single-use, short-lived tokens carry no archival value once the user is gone.
            verify(emailVerificationTokenRepository).deleteAllByUser(actor);
            verify(passwordResetTokenRepository).deleteAllByUser(actor);
        }
    }

    @Nested
    @DisplayName("updateProfile")
    class UpdateProfile {

        // ----- name / lastName -----

        @Test
        @DisplayName("should update name when only name is provided")
        void should_updateName_when_onlyNameProvided() {
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            User result = sut.updateProfile(actor, new UpdateUserProfileDto().setName("Nuevo"));

            assertEquals("Nuevo", result.getName());
            assertEquals("Test", result.getLastName());
            assertEquals("joan@test.com", result.getEmail());
        }

        @Test
        @DisplayName("should update lastName when only lastName is provided")
        void should_updateLastName_when_onlyLastNameProvided() {
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            User result = sut.updateProfile(actor, new UpdateUserProfileDto().setLastName("Apellido"));

            assertEquals("Joan", result.getName());
            assertEquals("Apellido", result.getLastName());
        }

        @Test
        @DisplayName("should update both name and lastName when both provided")
        void should_updateNameAndLastName_when_bothProvided() {
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            User result = sut.updateProfile(actor,
                    new UpdateUserProfileDto().setName("Nuevo").setLastName("Apellido"));

            assertEquals("Nuevo", result.getName());
            assertEquals("Apellido", result.getLastName());
        }

        @Test
        @DisplayName("should leave name unchanged when name is null in dto")
        void should_leaveName_unchanged_when_null() {
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            User result = sut.updateProfile(actor, new UpdateUserProfileDto().setLastName("X"));

            assertEquals("Joan", result.getName());
        }

        @Test
        @DisplayName("should leave lastName unchanged when lastName is null in dto")
        void should_leaveLastName_unchanged_when_null() {
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            User result = sut.updateProfile(actor, new UpdateUserProfileDto().setName("X"));

            assertEquals("Test", result.getLastName());
        }

        // ----- email -----

        @Test
        @DisplayName("should update email when email changes")
        void should_updateEmail_when_emailChanges() {
            when(userRepository.findByEmail("nuevo@test.com")).thenReturn(Optional.empty());
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            User result = sut.updateProfile(actor, new UpdateUserProfileDto().setEmail("nuevo@test.com"));

            assertEquals("nuevo@test.com", result.getEmail());
        }

        @Test
        @DisplayName("should reset emailVerified to false when email changes")
        void should_resetEmailVerified_when_emailChanges() {
            when(userRepository.findByEmail("nuevo@test.com")).thenReturn(Optional.empty());
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            User result = sut.updateProfile(actor, new UpdateUserProfileDto().setEmail("nuevo@test.com"));

            assertFalse(result.isEmailVerified());
        }

        @Test
        @DisplayName("should NOT reset emailVerified when email equals current")
        void should_NOT_resetEmailVerified_when_emailEqualsCurrent() {
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            User result = sut.updateProfile(actor, new UpdateUserProfileDto().setEmail("joan@test.com"));

            assertTrue(result.isEmailVerified());
        }

        @Test
        @DisplayName("should NOT call findByEmail when email is null in dto")
        void should_NOT_callFindByEmail_when_emailIsNull() {
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            sut.updateProfile(actor, new UpdateUserProfileDto().setName("X"));

            verify(userRepository, never()).findByEmail(any());
        }

        @Test
        @DisplayName("should NOT call findByEmail when email equals current (idempotent)")
        void should_NOT_callFindByEmail_when_emailEqualsCurrent() {
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            sut.updateProfile(actor, new UpdateUserProfileDto().setEmail("joan@test.com"));

            verify(userRepository, never()).findByEmail(any());
        }

        @Test
        @DisplayName("should throw 409 when new email is already used by another user")
        void should_throw409_when_newEmail_alreadyUsed_byAnotherUser() {
            User other = new User().setId(2).setEmail("nuevo@test.com");
            when(userRepository.findByEmail("nuevo@test.com")).thenReturn(Optional.of(other));

            ResponseStatusException ex = assertThrows(
                    ResponseStatusException.class,
                    () -> sut.updateProfile(actor, new UpdateUserProfileDto().setEmail("nuevo@test.com"))
            );

            assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
            verify(userRepository, never()).save(any(User.class));
        }

        @Test
        @DisplayName("should allow email change when findByEmail returns same user (no conflict)")
        void should_allowEmailChange_when_findByEmail_returnsSameUser() {
            // Defensive: even if the repo somehow returned the actor itself, it shouldn't be a conflict.
            when(userRepository.findByEmail("nuevo@test.com")).thenReturn(Optional.of(actor));
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            User result = sut.updateProfile(actor, new UpdateUserProfileDto().setEmail("nuevo@test.com"));

            assertEquals("nuevo@test.com", result.getEmail());
        }

        // ----- username -----

        @Test
        @DisplayName("should update username when username changes")
        void should_updateUsername_when_usernameChanges() {
            when(userRepository.findByUsername("nuevo")).thenReturn(Optional.empty());
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            User result = sut.updateProfile(actor, new UpdateUserProfileDto().setUsername("nuevo"));

            assertEquals("nuevo", result.getUsernameValue());
        }

        @Test
        @DisplayName("should NOT call findByUsername when username is null in dto")
        void should_NOT_callFindByUsername_when_usernameIsNull() {
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            sut.updateProfile(actor, new UpdateUserProfileDto().setName("X"));

            verify(userRepository, never()).findByUsername(any());
        }

        @Test
        @DisplayName("should NOT call findByUsername when username equals current (idempotent)")
        void should_NOT_callFindByUsername_when_usernameEqualsCurrent() {
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            sut.updateProfile(actor, new UpdateUserProfileDto().setUsername("joantest"));

            verify(userRepository, never()).findByUsername(any());
        }

        @Test
        @DisplayName("should throw 409 + users.username_taken when new username is already used by another user")
        void should_throw409_when_newUsername_alreadyUsed_byAnotherUser() {
            User other = new User().setId(2).setUsername("nuevo");
            when(userRepository.findByUsername("nuevo")).thenReturn(Optional.of(other));

            ApiException ex = assertThrows(
                    ApiException.class,
                    () -> sut.updateProfile(actor, new UpdateUserProfileDto().setUsername("nuevo"))
            );

            assertEquals(HttpStatus.CONFLICT, ex.getStatus());
            assertEquals(ErrorCodes.USERS_USERNAME_TAKEN, ex.getCode());
            verify(userRepository, never()).save(any(User.class));
        }

        @Test
        @DisplayName("should allow username change when findByUsername returns same user (no conflict)")
        void should_allowUsernameChange_when_findByUsername_returnsSameUser() {
            when(userRepository.findByUsername("nuevo")).thenReturn(Optional.of(actor));
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            User result = sut.updateProfile(actor, new UpdateUserProfileDto().setUsername("nuevo"));

            assertEquals("nuevo", result.getUsernameValue());
        }

        @Test
        @DisplayName("should throw 400 + users.username_invalid when new username has invalid format")
        void should_throw400_when_newUsername_invalidFormat() {
            ApiException ex = assertThrows(
                    ApiException.class,
                    () -> sut.updateProfile(actor, new UpdateUserProfileDto().setUsername("Bad-User!"))
            );

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertEquals(ErrorCodes.USERS_USERNAME_INVALID, ex.getCode());
            verify(userRepository, never()).save(any(User.class));
        }

        @Test
        @DisplayName("should throw 400 + users.username_reserved when new username is reserved")
        void should_throw400_when_newUsername_reserved() {
            ApiException ex = assertThrows(
                    ApiException.class,
                    () -> sut.updateProfile(actor, new UpdateUserProfileDto().setUsername("admin"))
            );

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertEquals(ErrorCodes.USERS_USERNAME_RESERVED, ex.getCode());
            verify(userRepository, never()).save(any(User.class));
        }

        @Test
        @DisplayName("should throw 409 + users.username_taken when new username belongs to another active user history")
        void should_throw409_when_newUsername_inOtherUserHistory() {
            User other = new User().setId(2);
            UserUsernameHistory entry = new UserUsernameHistory().setUser(other).setOldUsername("nuevo");
            when(userRepository.findByUsername("nuevo")).thenReturn(Optional.empty());
            when(usernameHistoryRepository.findByOldUsername("nuevo")).thenReturn(Optional.of(entry));

            ApiException ex = assertThrows(
                    ApiException.class,
                    () -> sut.updateProfile(actor, new UpdateUserProfileDto().setUsername("nuevo"))
            );

            assertEquals(HttpStatus.CONFLICT, ex.getStatus());
            assertEquals(ErrorCodes.USERS_USERNAME_TAKEN, ex.getCode());
            verify(userRepository, never()).save(any(User.class));
        }

        @Test
        @DisplayName("should allow re-claim of a previous username from the actor's own history")
        void should_allowReclaim_when_inOwnHistory() {
            // The actor previously used "old" and renamed to "joantest". Now they want to revert.
            UserUsernameHistory entry = new UserUsernameHistory().setUser(actor).setOldUsername("old");
            when(userRepository.findByUsername("old")).thenReturn(Optional.empty());
            when(usernameHistoryRepository.findByOldUsername("old")).thenReturn(Optional.of(entry));
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            User result = sut.updateProfile(actor, new UpdateUserProfileDto().setUsername("old"));

            assertEquals("old", result.getUsernameValue());
            ArgumentCaptor<UserUsernameHistory> savedHistory = ArgumentCaptor.forClass(UserUsernameHistory.class);
            verify(usernameHistoryRepository).save(savedHistory.capture());
            // The actor's previous active username ("joantest") is the one we just archived; "old"
            // was already in the actor's history before the swap.
            assertEquals("joantest", savedHistory.getValue().getOldUsername());
        }

        // ----- combinations / no-op -----

        @Test
        @DisplayName("should be no-op when dto has all fields null")
        void should_beNoop_when_allFieldsNull() {
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            User result = sut.updateProfile(actor, new UpdateUserProfileDto());

            assertEquals("Joan", result.getName());
            assertEquals("Test", result.getLastName());
            assertEquals("joan@test.com", result.getEmail());
            assertEquals("joantest", result.getUsernameValue());
            assertTrue(result.isEmailVerified());
            verify(userRepository, never()).findByEmail(any());
            verify(userRepository, never()).findByUsername(any());
        }

        @Test
        @DisplayName("should update all fields when all are provided")
        void should_updateAllFields_when_allProvided() {
            when(userRepository.findByEmail("nuevo@test.com")).thenReturn(Optional.empty());
            when(userRepository.findByUsername("nuevouser")).thenReturn(Optional.empty());
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            User result = sut.updateProfile(actor, new UpdateUserProfileDto()
                    .setName("Nuevo")
                    .setLastName("Apellido")
                    .setEmail("nuevo@test.com")
                    .setUsername("nuevouser"));

            assertEquals("Nuevo", result.getName());
            assertEquals("Apellido", result.getLastName());
            assertEquals("nuevo@test.com", result.getEmail());
            assertEquals("nuevouser", result.getUsernameValue());
            assertFalse(result.isEmailVerified());
        }

        @Test
        @DisplayName("should call save with the actor and return its result")
        void should_save_andReturnSavedInstance() {
            User saved = new User().setId(1).setName("Persisted");
            ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
            when(userRepository.save(captor.capture())).thenReturn(saved);

            User result = sut.updateProfile(actor, new UpdateUserProfileDto().setName("Nuevo"));

            assertSame(actor, captor.getValue());
            assertSame(saved, result);
        }

        // ----- language -----

        @Test
        @DisplayName("should update language when language is provided")
        void should_updateLanguage_when_provided() {
            actor.setLanguage(Language.ES);
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            User result = sut.updateProfile(actor, new UpdateUserProfileDto().setLanguage("EN"));

            assertEquals(Language.EN, result.getLanguage());
        }

        @Test
        @DisplayName("should leave language unchanged when null in dto (PATCH partial)")
        void should_leaveLanguage_unchanged_when_null() {
            actor.setLanguage(Language.CA);
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            User result = sut.updateProfile(actor, new UpdateUserProfileDto().setName("X"));

            assertEquals(Language.CA, result.getLanguage());
        }

        @Test
        @DisplayName("should throw 400 when language is invalid")
        void should_throw400_when_languageInvalid() {
            actor.setLanguage(Language.ES);

            ResponseStatusException ex = assertThrows(
                    ResponseStatusException.class,
                    () -> sut.updateProfile(actor, new UpdateUserProfileDto().setLanguage("XX"))
            );

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
            verify(userRepository, never()).save(any(User.class));
        }
    }

    @Nested
    @DisplayName("changePassword")
    class ChangePassword {

        private ChangePasswordDto valid() {
            return new ChangePasswordDto()
                    .setCurrentPassword("oldPwd123")
                    .setNewPassword("newPwd123")
                    .setRepeatPassword("newPwd123");
        }

        @Test
        @DisplayName("should encode new password and update passwordChangedAt when input is valid")
        void should_encodeAndPersist_when_valid() {
            when(passwordEncoder.matches("oldPwd123", "hashed")).thenReturn(true);
            when(passwordEncoder.matches("newPwd123", "hashed")).thenReturn(false);
            when(passwordEncoder.encode("newPwd123")).thenReturn("hashed-new");
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            sut.changePassword(actor, valid());

            assertEquals("hashed-new", actor.getPassword());
            assertNotNull(actor.getPasswordChangedAt());
            verify(userRepository).save(actor);
        }

        @Test
        @DisplayName("should throw 400 with current_password_invalid when currentPassword is null")
        void should_throw400_when_currentPasswordIsNull() {
            ChangePasswordDto dto = valid().setCurrentPassword(null);

            ApiException ex = assertThrows(ApiException.class, () -> sut.changePassword(actor, dto));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertEquals(ErrorCodes.AUTH_CURRENT_PASSWORD_INVALID, ex.getCode());
            verify(userRepository, never()).save(any(User.class));
        }

        @Test
        @DisplayName("should throw 400 with current_password_invalid when currentPassword is blank")
        void should_throw400_when_currentPasswordIsBlank() {
            ChangePasswordDto dto = valid().setCurrentPassword("   ");

            ApiException ex = assertThrows(ApiException.class, () -> sut.changePassword(actor, dto));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertEquals(ErrorCodes.AUTH_CURRENT_PASSWORD_INVALID, ex.getCode());
            verify(userRepository, never()).save(any(User.class));
        }

        @Test
        @DisplayName("should throw 400 with password_too_short when newPassword is null")
        void should_throw400_when_newPasswordIsNull() {
            ChangePasswordDto dto = valid().setNewPassword(null);

            ApiException ex = assertThrows(ApiException.class, () -> sut.changePassword(actor, dto));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertEquals(ErrorCodes.AUTH_PASSWORD_TOO_SHORT, ex.getCode());
            verify(userRepository, never()).save(any(User.class));
        }

        @Test
        @DisplayName("should throw 400 with password_too_short when newPassword is shorter than 8")
        void should_throw400_when_newPasswordTooShort() {
            ChangePasswordDto dto = valid().setNewPassword("short").setRepeatPassword("short");

            ApiException ex = assertThrows(ApiException.class, () -> sut.changePassword(actor, dto));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertEquals(ErrorCodes.AUTH_PASSWORD_TOO_SHORT, ex.getCode());
            assertEquals(8, ex.getParams().get("min"));
            verify(userRepository, never()).save(any(User.class));
        }

        @Test
        @DisplayName("should throw 400 with passwords_mismatch when newPassword and repeatPassword differ")
        void should_throw400_when_passwordsMismatch() {
            ChangePasswordDto dto = valid().setRepeatPassword("differentPwd123");

            ApiException ex = assertThrows(ApiException.class, () -> sut.changePassword(actor, dto));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertEquals(ErrorCodes.AUTH_PASSWORDS_MISMATCH, ex.getCode());
            verify(userRepository, never()).save(any(User.class));
        }

        @Test
        @DisplayName("should throw 401 with current_password_invalid when currentPassword does not match the stored hash")
        void should_throw401_when_currentPasswordDoesNotMatch() {
            when(passwordEncoder.matches("oldPwd123", "hashed")).thenReturn(false);

            ApiException ex = assertThrows(ApiException.class, () -> sut.changePassword(actor, valid()));

            assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
            assertEquals(ErrorCodes.AUTH_CURRENT_PASSWORD_INVALID, ex.getCode());
            verify(userRepository, never()).save(any(User.class));
            verify(passwordEncoder, never()).encode(any());
        }

        @Test
        @DisplayName("should throw 400 with new_password_same_as_current when new password equals current")
        void should_throw400_when_newPasswordEqualsCurrent() {
            when(passwordEncoder.matches("oldPwd123", "hashed")).thenReturn(true);
            when(passwordEncoder.matches("newPwd123", "hashed")).thenReturn(true);

            ApiException ex = assertThrows(ApiException.class, () -> sut.changePassword(actor, valid()));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertEquals(ErrorCodes.AUTH_NEW_PASSWORD_SAME_AS_CURRENT, ex.getCode());
            verify(userRepository, never()).save(any(User.class));
            verify(passwordEncoder, never()).encode(any());
        }

        @Test
        @DisplayName("should validate currentPassword length BEFORE invoking passwordEncoder.matches")
        void should_skipEncoder_when_validationFailsEarly() {
            ChangePasswordDto dto = valid().setCurrentPassword("");

            assertThrows(ApiException.class, () -> sut.changePassword(actor, dto));

            verify(passwordEncoder, never()).matches(any(), any());
            verify(passwordEncoder, never()).encode(any());
        }
    }

    @Nested
    @DisplayName("checkUsernameAvailability")
    class CheckUsernameAvailability {

        @org.junit.jupiter.params.ParameterizedTest(name = "[{index}] invalid: \"{0}\"")
        @org.junit.jupiter.params.provider.NullAndEmptySource
        @org.junit.jupiter.params.provider.ValueSource(strings = {
                "ab",                       // too short
                "abcdefghijklmnopqrstuvwxy", // too long (25)
                "Joan",                     // uppercase
                "joan test",                // space
                "joan-test",                // hyphen
                "joan_test",                // underscore
                "joan.test",                // dot
                "joáñ",                     // unicode
                "joan!"                     // special char
        })
        @DisplayName("should return INVALID_FORMAT for malformed usernames")
        void should_returnInvalidFormat_when_malformed(String input) {
            AvailabilityResponse res = sut.checkUsernameAvailability(input);

            assertFalse(res.available());
            assertEquals(Reason.INVALID_FORMAT, res.reason());
        }

        @org.junit.jupiter.params.ParameterizedTest(name = "[{index}] reserved: {0}")
        @org.junit.jupiter.params.provider.ValueSource(strings = {
                "admin", "api", "root", "support", "system", "stocka",
                "auth", "users", "www", "app", "health"
        })
        @DisplayName("should return RESERVED for reserved usernames")
        void should_returnReserved_when_reserved(String input) {
            AvailabilityResponse res = sut.checkUsernameAvailability(input);

            assertFalse(res.available());
            assertEquals(Reason.RESERVED, res.reason());
        }

        @Test
        @DisplayName("should return TAKEN when the username already exists on an active user")
        void should_returnTaken_when_usernameExists() {
            when(userRepository.existsByUsername("joantest")).thenReturn(true);

            AvailabilityResponse res = sut.checkUsernameAvailability("joantest");

            assertFalse(res.available());
            assertEquals(Reason.TAKEN, res.reason());
        }

        @Test
        @DisplayName("should return TAKEN when the username belongs to an active user's history")
        void should_returnTaken_when_usernameInActiveUserHistory() {
            UserUsernameHistory entry = new UserUsernameHistory()
                    .setUser(new User().setId(7))
                    .setOldUsername("joantest");
            when(userRepository.existsByUsername("joantest")).thenReturn(false);
            when(usernameHistoryRepository.findByOldUsername("joantest"))
                    .thenReturn(Optional.of(entry));

            AvailabilityResponse res = sut.checkUsernameAvailability("joantest");

            assertFalse(res.available());
            assertEquals(Reason.TAKEN, res.reason());
        }

        @Test
        @DisplayName("should return available when format is valid, not reserved, and free")
        void should_returnAvailable_when_validAndFree() {
            when(userRepository.existsByUsername("joantest")).thenReturn(false);

            AvailabilityResponse res = sut.checkUsernameAvailability("joantest");

            assertTrue(res.available());
            assertNull(res.reason());
        }

        @Test
        @DisplayName("should accept usernames at the boundary lengths (3 and 24)")
        void should_returnAvailable_when_lengthAtBoundaries() {
            when(userRepository.existsByUsername("abc")).thenReturn(false);
            when(userRepository.existsByUsername("abcdefghijklmnopqrstuvwx")).thenReturn(false);

            assertTrue(sut.checkUsernameAvailability("abc").available());
            assertTrue(sut.checkUsernameAvailability("abcdefghijklmnopqrstuvwx").available());
        }
    }
}
