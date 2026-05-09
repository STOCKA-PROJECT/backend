package com.stocka.backend.modules.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.stocka.backend.modules.roles.entity.Role;
import com.stocka.backend.modules.roles.entity.RoleEnum;
import com.stocka.backend.modules.roles.repository.RoleRepository;
import com.stocka.backend.modules.users.entity.User;
import com.stocka.backend.modules.users.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminSeeder")
class AdminSeederTest {

    @Mock private RoleRepository roleRepository;
    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private ContextRefreshedEvent event;

    private AdminSeeder buildSeeder(String email, String password) {
        return new AdminSeeder(roleRepository, userRepository, passwordEncoder, email, password);
    }

    @Nested
    @DisplayName("when bootstrap variables are missing")
    class WhenVariablesMissing {

        @Test
        @DisplayName("does nothing if email and password are blank")
        void doesNothingIfBothBlank() {
            AdminSeeder sut = buildSeeder("", "");

            sut.onApplicationEvent(event);

            verifyNoInteractions(roleRepository, userRepository, passwordEncoder);
        }

        @Test
        @DisplayName("does nothing if email is blank")
        void doesNothingIfEmailBlank() {
            AdminSeeder sut = buildSeeder("   ", "secret-pass");

            sut.onApplicationEvent(event);

            verifyNoInteractions(roleRepository, userRepository, passwordEncoder);
        }

        @Test
        @DisplayName("does nothing if password is blank")
        void doesNothingIfPasswordBlank() {
            AdminSeeder sut = buildSeeder("admin@example.com", "");

            sut.onApplicationEvent(event);

            verifyNoInteractions(roleRepository, userRepository, passwordEncoder);
        }
    }

    @Nested
    @DisplayName("when bootstrap variables are valid")
    class WhenVariablesValid {

        @Test
        @DisplayName("creates admin with emailVerified=false and mustChangePassword=true")
        void createsAdminWithSafeFlags() {
            Role adminRole = new Role().setName(RoleEnum.ADMIN);
            when(roleRepository.findByName(RoleEnum.ADMIN)).thenReturn(Optional.of(adminRole));
            when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.empty());
            when(passwordEncoder.encode("super-secret")).thenReturn("hashed-password");

            AdminSeeder sut = buildSeeder("admin@example.com", "super-secret");
            sut.onApplicationEvent(event);

            ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(captor.capture());

            User saved = captor.getValue();
            assertThat(saved.getEmail()).isEqualTo("admin@example.com");
            assertThat(saved.getUsernameValue()).isEqualTo("admin");
            assertThat(saved.getPassword()).isEqualTo("hashed-password");
            assertThat(saved.isEmailVerified()).isFalse();
            assertThat(saved.isMustChangePassword()).isTrue();
            assertThat(saved.getRole()).isSameAs(adminRole);
        }

        @Test
        @DisplayName("does nothing when ADMIN role is missing")
        void skipsWhenAdminRoleMissing() {
            when(roleRepository.findByName(RoleEnum.ADMIN)).thenReturn(Optional.empty());

            AdminSeeder sut = buildSeeder("admin@example.com", "super-secret");
            sut.onApplicationEvent(event);

            verify(userRepository, never()).save(any());
            verifyNoInteractions(passwordEncoder);
        }
    }

    @Nested
    @DisplayName("when admin already exists")
    class WhenAdminExists {

        @Test
        @DisplayName("skips creation without saving or encoding the password")
        void skipsWhenUserAlreadyExists() {
            Role adminRole = new Role().setName(RoleEnum.ADMIN);
            when(roleRepository.findByName(RoleEnum.ADMIN)).thenReturn(Optional.of(adminRole));
            when(userRepository.findByEmail("admin@example.com"))
                    .thenReturn(Optional.of(new User().setEmail("admin@example.com")));

            AdminSeeder sut = buildSeeder("admin@example.com", "super-secret");
            sut.onApplicationEvent(event);

            verify(userRepository, never()).save(any());
            verifyNoInteractions(passwordEncoder);
        }
    }
}
