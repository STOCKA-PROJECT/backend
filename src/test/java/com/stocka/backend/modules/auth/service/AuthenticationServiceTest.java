package com.stocka.backend.modules.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.stocka.backend.modules.auth.dto.LoginUserDto;
import com.stocka.backend.modules.auth.dto.RegisterUserDto;
import com.stocka.backend.modules.common.dto.AvailabilityResponse;
import com.stocka.backend.modules.common.dto.AvailabilityResponse.Reason;
import com.stocka.backend.modules.common.error.ApiException;
import com.stocka.backend.modules.common.error.ErrorCodes;
import com.stocka.backend.modules.roles.entity.Role;
import com.stocka.backend.modules.roles.entity.RoleEnum;
import com.stocka.backend.modules.roles.repository.RoleRepository;
import com.stocka.backend.modules.security.entity.InvalidatedToken;
import com.stocka.backend.modules.security.repository.InvalidatedTokenRepository;
import com.stocka.backend.modules.security.service.JwtService;
import com.stocka.backend.modules.users.entity.User;
import com.stocka.backend.modules.users.repository.UserRepository;
import com.stocka.backend.modules.users.service.UserService;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthenticationService")
class AuthenticationServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private UserService userService;
    @Mock private RoleRepository roleRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private AuthenticationManager authenticationManager;
    @Mock private InvalidatedTokenRepository invalidatedTokenRepository;
    @Mock private JwtService jwtService;
    @Mock private EmailVerificationService emailVerificationService;
    @Mock private RefreshTokenService refreshTokenService;
    @Mock private com.stocka.backend.modules.security.audit.SecurityAuditService securityAuditService;

    @InjectMocks
    private AuthenticationService sut;

    private Role userRole;
    private RegisterUserDto validDto;

    @BeforeEach
    void setUp() {
        userRole = new Role().setName(RoleEnum.USER).setDescription("User role");
        validDto = new RegisterUserDto()
                .setName("Joan")
                .setLastName("Test")
                .setUsername("joantest")
                .setEmail("joan@test.com")
                .setPassword("password123")
                .setRepeatPassword("password123");
    }

    @Nested
    @DisplayName("signup")
    class Signup {

        @Test
        @DisplayName("should return the saved user when input is valid")
        void should_returnSavedUser_when_inputIsValid() {
            when(userRepository.findByEmail(any())).thenReturn(Optional.empty());
            when(userService.checkUsernameAvailability(validDto.getUsername()))
                    .thenReturn(AvailabilityResponse.ok());
            when(roleRepository.findByName(RoleEnum.USER)).thenReturn(Optional.of(userRole));
            when(passwordEncoder.encode(any())).thenReturn("hashed");
            User expected = new User();
            when(userRepository.save(any(User.class))).thenReturn(expected);

            User result = sut.signup(validDto);

            assertSame(expected, result);
            verify(userRepository).save(any(User.class));
            verify(emailVerificationService).sendVerificationEmail(expected);
        }

        @Test
        @DisplayName("should throw 400 + auth.passwords_mismatch when passwords do not match")
        void should_throwBadRequest_when_passwordsDoNotMatch() {
            validDto.setRepeatPassword("different");

            ApiException ex = assertThrows(
                    ApiException.class,
                    () -> sut.signup(validDto)
            );

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertEquals(ErrorCodes.AUTH_PASSWORDS_MISMATCH, ex.getCode());
        }

        @Test
        @DisplayName("should throw 409 + users.email_taken when email is already registered")
        void should_throwConflict_when_emailAlreadyExists() {
            when(userRepository.findByEmail(validDto.getEmail())).thenReturn(Optional.of(new User()));

            ApiException ex = assertThrows(
                    ApiException.class,
                    () -> sut.signup(validDto)
            );

            assertEquals(HttpStatus.CONFLICT, ex.getStatus());
            assertEquals(ErrorCodes.USERS_EMAIL_TAKEN, ex.getCode());
        }

        @Test
        @DisplayName("should throw 409 + users.username_taken when username is already taken")
        void should_throwConflict_when_usernameAlreadyExists() {
            when(userRepository.findByEmail(any())).thenReturn(Optional.empty());
            when(userService.checkUsernameAvailability(validDto.getUsername()))
                    .thenReturn(AvailabilityResponse.unavailable(Reason.TAKEN));

            ApiException ex = assertThrows(
                    ApiException.class,
                    () -> sut.signup(validDto)
            );

            assertEquals(HttpStatus.CONFLICT, ex.getStatus());
            assertEquals(ErrorCodes.USERS_USERNAME_TAKEN, ex.getCode());
        }

        @Test
        @DisplayName("should throw 400 + users.username_invalid when username has invalid format")
        void should_throwBadRequest_when_usernameInvalidFormat() {
            validDto.setUsername("Joan-Test!");
            when(userRepository.findByEmail(any())).thenReturn(Optional.empty());
            when(userService.checkUsernameAvailability("Joan-Test!"))
                    .thenReturn(AvailabilityResponse.unavailable(Reason.INVALID_FORMAT));

            ApiException ex = assertThrows(
                    ApiException.class,
                    () -> sut.signup(validDto)
            );

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertEquals(ErrorCodes.USERS_USERNAME_INVALID, ex.getCode());
        }

        @Test
        @DisplayName("should throw 400 + users.username_reserved when username is reserved")
        void should_throwBadRequest_when_usernameReserved() {
            validDto.setUsername("admin");
            when(userRepository.findByEmail(any())).thenReturn(Optional.empty());
            when(userService.checkUsernameAvailability("admin"))
                    .thenReturn(AvailabilityResponse.unavailable(Reason.RESERVED));

            ApiException ex = assertThrows(
                    ApiException.class,
                    () -> sut.signup(validDto)
            );

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertEquals(ErrorCodes.USERS_USERNAME_RESERVED, ex.getCode());
        }

        @Test
        @DisplayName("should throw IllegalStateException when USER role does not exist in DB")
        void should_throwIllegalState_when_userRoleNotFound() {
            when(userRepository.findByEmail(any())).thenReturn(Optional.empty());
            when(userService.checkUsernameAvailability(validDto.getUsername()))
                    .thenReturn(AvailabilityResponse.ok());
            when(roleRepository.findByName(RoleEnum.USER)).thenReturn(Optional.empty());

            assertThrows(
                    IllegalStateException.class,
                    () -> sut.signup(validDto)
            );
        }

        @Test
        @DisplayName("should set emailVerified=false on the created user (verification flow gates login)")
        void should_setEmailVerifiedFalse_on_createdUser() {
            when(userRepository.findByEmail(any())).thenReturn(Optional.empty());
            when(userService.checkUsernameAvailability(validDto.getUsername()))
                    .thenReturn(AvailabilityResponse.ok());
            when(roleRepository.findByName(RoleEnum.USER)).thenReturn(Optional.of(userRole));
            when(passwordEncoder.encode(any())).thenReturn("hashed");
            ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
            when(userRepository.save(captor.capture())).thenReturn(new User());

            sut.signup(validDto);

            assertFalse(captor.getValue().isEmailVerified());
        }

        @Test
        @DisplayName("should send the verification email after persisting the user")
        void should_sendVerificationEmail_after_save() {
            when(userRepository.findByEmail(any())).thenReturn(Optional.empty());
            when(userService.checkUsernameAvailability(validDto.getUsername()))
                    .thenReturn(AvailabilityResponse.ok());
            when(roleRepository.findByName(RoleEnum.USER)).thenReturn(Optional.of(userRole));
            when(passwordEncoder.encode(any())).thenReturn("hashed");
            User saved = new User();
            when(userRepository.save(any(User.class))).thenReturn(saved);
            InOrder ordered = inOrder(userRepository, emailVerificationService);

            sut.signup(validDto);

            ordered.verify(userRepository).save(any(User.class));
            ordered.verify(emailVerificationService).sendVerificationEmail(saved);
        }

        @Test
        @DisplayName("should persist encoded password, not the raw one")
        void should_encodePassword_on_createdUser() {
            when(userRepository.findByEmail(any())).thenReturn(Optional.empty());
            when(userService.checkUsernameAvailability(validDto.getUsername()))
                    .thenReturn(AvailabilityResponse.ok());
            when(roleRepository.findByName(RoleEnum.USER)).thenReturn(Optional.of(userRole));
            when(passwordEncoder.encode("password123")).thenReturn("hashed_value");
            ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
            when(userRepository.save(captor.capture())).thenReturn(new User());

            sut.signup(validDto);

            assertEquals("hashed_value", captor.getValue().getPassword());
        }
    }

    @Nested
    @DisplayName("checkUsernameAvailability")
    class CheckUsernameAvailability {

        // The full validation matrix (format, reserved, taken, history) lives in
        // UserServiceTest; here we just verify the delegate returns whatever UserService
        // computed without altering it.

        @Test
        @DisplayName("should delegate to UserService and return its result unchanged")
        void should_delegateToUserService() {
            AvailabilityResponse expected = AvailabilityResponse.unavailable(Reason.TAKEN);
            when(userService.checkUsernameAvailability("joantest")).thenReturn(expected);

            AvailabilityResponse res = sut.checkUsernameAvailability("joantest");

            assertSame(expected, res);
            verify(userService).checkUsernameAvailability("joantest");
        }
    }

    @Nested
    @DisplayName("authenticate")
    class Authenticate {

        @Test
        @DisplayName("should return the authenticated user when credentials are valid")
        void should_returnUser_when_credentialsAreValid() {
            User user = new User().setEmailVerified(true);
            Authentication auth = mock(Authentication.class);
            when(auth.getPrincipal()).thenReturn(user);
            when(authenticationManager.authenticate(any())).thenReturn(auth);

            User result = sut.authenticate(new LoginUserDto()
                    .setEmail("joan@test.com")
                    .setPassword("password123"));

            assertSame(user, result);
        }

        @Test
        @DisplayName("should throw 403 + auth.email_not_verified when email is not verified")
        void should_throwForbidden_when_emailNotVerified() {
            User user = new User().setEmailVerified(false);
            Authentication auth = mock(Authentication.class);
            when(auth.getPrincipal()).thenReturn(user);
            when(authenticationManager.authenticate(any())).thenReturn(auth);

            ApiException ex = assertThrows(
                    ApiException.class,
                    () -> sut.authenticate(new LoginUserDto()
                            .setEmail("joan@test.com")
                            .setPassword("password123"))
            );

            assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
            assertEquals(ErrorCodes.AUTH_EMAIL_NOT_VERIFIED, ex.getCode());
        }

        @Test
        @DisplayName("should propagate BadCredentialsException when password is wrong")
        void should_propagateException_when_badCredentials() {
            when(authenticationManager.authenticate(any()))
                    .thenThrow(new BadCredentialsException("Bad credentials"));

            assertThrows(
                    BadCredentialsException.class,
                    () -> sut.authenticate(new LoginUserDto()
                            .setEmail("joan@test.com")
                            .setPassword("wrong"))
            );
        }

        @Test
        @DisplayName("should propagate BadCredentialsException when user does not exist")
        void should_propagateException_when_userNotFound() {
            when(authenticationManager.authenticate(any()))
                    .thenThrow(new BadCredentialsException("User not found"));

            assertThrows(
                    BadCredentialsException.class,
                    () -> sut.authenticate(new LoginUserDto()
                            .setEmail("nobody@test.com")
                            .setPassword("password123"))
            );
        }
    }

    @Nested
    @DisplayName("logout")
    class Logout {

        @Test
        @DisplayName("should save the token in the blocklist with its expiry")
        void should_saveInvalidatedToken_with_correctExpiry() {
            String token = "header.payload.signature";
            LocalDateTime expiry = LocalDateTime.now().plusHours(1);
            when(jwtService.extractExpirationAsLocalDateTime(token)).thenReturn(expiry);
            ArgumentCaptor<InvalidatedToken> captor = ArgumentCaptor.forClass(InvalidatedToken.class);

            sut.logout(token, null);

            verify(invalidatedTokenRepository).save(captor.capture());
            assertEquals(token, captor.getValue().getToken());
            assertEquals(expiry, captor.getValue().getExpiresAt());
        }

        @Test
        @DisplayName("should delete expired tokens before saving the new invalidated token")
        void should_deleteExpiredTokensFirst_then_save() {
            String token = "header.payload.signature";
            when(jwtService.extractExpirationAsLocalDateTime(token))
                    .thenReturn(LocalDateTime.now().plusHours(1));
            InOrder inOrder = inOrder(invalidatedTokenRepository);

            sut.logout(token, null);

            inOrder.verify(invalidatedTokenRepository).deleteExpired(any(LocalDateTime.class));
            inOrder.verify(invalidatedTokenRepository).save(any(InvalidatedToken.class));
        }
    }
}
