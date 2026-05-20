package com.stocka.backend.modules.users.service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.stocka.backend.modules.common.dto.AvailabilityResponse;
import com.stocka.backend.modules.common.dto.AvailabilityResponse.Reason;
import com.stocka.backend.modules.common.error.ApiException;
import com.stocka.backend.modules.common.error.ErrorCodes;
import com.stocka.backend.modules.notifications.preferences.service.NotificationPreferenceService;
import com.stocka.backend.modules.organizations.entity.OrganizationMember;
import com.stocka.backend.modules.organizations.repository.OrganizationMemberRepository;
import com.stocka.backend.modules.users.dto.ChangePasswordDto;
import com.stocka.backend.modules.users.dto.UpdateUserProfileDto;
import com.stocka.backend.modules.users.entity.Language;
import com.stocka.backend.modules.users.entity.User;
import com.stocka.backend.modules.users.entity.UserUsernameHistory;
import com.stocka.backend.modules.users.repository.UserRepository;
import com.stocka.backend.modules.users.repository.UserUsernameHistoryRepository;

@Service
public class UserService {

    private static final int MIN_PASSWORD_LENGTH = 8;

    static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-z0-9]{3,24}$");

    static final Set<String> RESERVED_USERNAMES = Set.of(
            "admin", "api", "root", "support", "system", "stocka",
            "auth", "users", "www", "app", "health",
            "null", "undefined"
    );

    private final UserRepository userRepository;
    private final UserUsernameHistoryRepository usernameHistoryRepository;
    private final OrganizationMemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final NotificationPreferenceService notificationPreferenceService;

    public UserService(
            UserRepository userRepository,
            UserUsernameHistoryRepository usernameHistoryRepository,
            OrganizationMemberRepository memberRepository,
            PasswordEncoder passwordEncoder,
            NotificationPreferenceService notificationPreferenceService) {
        this.userRepository = userRepository;
        this.usernameHistoryRepository = usernameHistoryRepository;
        this.memberRepository = memberRepository;
        this.passwordEncoder = passwordEncoder;
        this.notificationPreferenceService = notificationPreferenceService;
    }

    public List<User> allUsers() {
        List<User> users = new ArrayList<>();
        userRepository.findAll().forEach(users::add);
        return users;
    }

    @Transactional
    public void softDeleteCurrentUser(User user) {
        LocalDateTime now = LocalDateTime.now();
        for (OrganizationMember m : memberRepository.findByUser(user)) {
            if (m.getDeletedAt() == null) {
                m.setDeletedAt(now);
                memberRepository.save(m);
            }
        }
        // Notification preferences are bound to (user, org) pairs; with the user gone
        // they are dead data — soft-delete them so a future restoration does not
        // resurrect stale opt-ins.
        notificationPreferenceService.softDeleteAllFor(user);

        // Release the username so anyone can claim it after the user is gone: drop every history
        // row (frees previously used usernames) and rename the active username to a marker that
        // fails USERNAME_PATTERN so it cannot collide with a real one. The row stays for audit.
        usernameHistoryRepository.deleteByUser(user);
        user.setUsername("__deleted_" + user.getId() + "_" + now.toEpochSecond(ZoneOffset.UTC) + "__");
        user.setDeletedAt(now);
        userRepository.save(user);
    }

    @Transactional
    public User updateProfile(User actor, UpdateUserProfileDto dto) {
        if (dto.getEmail() != null && !dto.getEmail().equals(actor.getEmail())) {
            Optional<User> existing = userRepository.findByEmail(dto.getEmail());
            if (existing.isPresent() && !existing.get().getId().equals(actor.getId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Ya existe un usuario con ese email");
            }
            actor.setEmail(dto.getEmail());
            actor.setEmailVerified(false);
        }

        String previousUsername = null;
        if (dto.getUsername() != null && !dto.getUsername().equals(actor.getUsernameValue())) {
            validateUsername(dto.getUsername(), actor.getId());
            previousUsername = actor.getUsernameValue();
            actor.setUsername(dto.getUsername());
        }

        if (dto.getName() != null) {
            actor.setName(dto.getName());
        }

        if (dto.getLastName() != null) {
            actor.setLastName(dto.getLastName());
        }

        if (dto.getLanguage() != null) {
            actor.setLanguage(Language.fromString(dto.getLanguage()));
        }

        User saved = userRepository.save(actor);
        if (previousUsername != null) {
            usernameHistoryRepository.save(new UserUsernameHistory()
                    .setUser(saved)
                    .setOldUsername(previousUsername));
        }
        return saved;
    }

    /**
     * Checks whether the given username can be used to register a new user. A username is
     * available when it has the right format, is not reserved, no active user holds it and no
     * history entry pointing to an active user matches it. History rows whose owner is
     * soft-deleted are transparently hidden by the {@code @SQLRestriction} on {@link User},
     * which encodes the "username is released when the owner is gone" policy.
     *
     * @param username candidate username; may be {@code null}
     * @return an {@link AvailabilityResponse} describing the result; never {@code null}
     */
    public AvailabilityResponse checkUsernameAvailability(String username) {
        if (username == null || !USERNAME_PATTERN.matcher(username).matches()) {
            return AvailabilityResponse.unavailable(Reason.INVALID_FORMAT);
        }
        if (RESERVED_USERNAMES.contains(username)) {
            return AvailabilityResponse.unavailable(Reason.RESERVED);
        }
        if (userRepository.existsByUsername(username)) {
            return AvailabilityResponse.unavailable(Reason.TAKEN);
        }
        if (usernameHistoryRepository.findByOldUsername(username).isPresent()) {
            return AvailabilityResponse.unavailable(Reason.TAKEN);
        }
        return AvailabilityResponse.ok();
    }

    /**
     * Cambia la contraseña del usuario autenticado.
     *
     * <p>Valida en orden: que {@code currentPassword} no sea nulo/blanco, que
     * {@code newPassword} tenga al menos {@value #MIN_PASSWORD_LENGTH} caracteres,
     * que coincida con {@code repeatPassword}, que {@code currentPassword} encaje
     * con el hash almacenado y que la nueva contraseña sea distinta de la actual.
     * En caso de fallo lanza un {@link ApiException} con el {@code code} estable
     * correspondiente, que el handler global traduce vía {@code MessageSource}.
     *
     * <p>Tras un cambio exitoso, el JWT que el usuario está usando queda inválido
     * porque {@code JwtAuthenticationFilter} rechaza cualquier token emitido antes
     * de {@code passwordChangedAt}. El cliente debe re-autenticarse con la nueva
     * contraseña para obtener un token nuevo.
     *
     * @param actor usuario autenticado que solicita el cambio
     * @param dto datos con la contraseña actual y la nueva por duplicado
     * @throws ApiException con {@code 400} y {@code auth.current_password_invalid} si
     *     {@code currentPassword} es nulo o blanco
     * @throws ApiException con {@code 400} y {@code auth.password_too_short} si
     *     {@code newPassword} es nula o más corta que {@value #MIN_PASSWORD_LENGTH}
     * @throws ApiException con {@code 400} y {@code auth.passwords_mismatch} si
     *     {@code newPassword} y {@code repeatPassword} no coinciden
     * @throws ApiException con {@code 401} y {@code auth.current_password_invalid} si
     *     {@code currentPassword} no coincide con el hash almacenado
     * @throws ApiException con {@code 400} y {@code auth.new_password_same_as_current}
     *     si la nueva contraseña coincide con la actual
     */
    @Transactional
    public void changePassword(User actor, ChangePasswordDto dto) {
        String currentPassword = dto.getCurrentPassword();
        if (currentPassword == null || currentPassword.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.AUTH_CURRENT_PASSWORD_INVALID);
        }

        String newPassword = dto.getNewPassword();
        if (newPassword == null || newPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    ErrorCodes.AUTH_PASSWORD_TOO_SHORT,
                    Map.of("min", MIN_PASSWORD_LENGTH));
        }

        if (!newPassword.equals(dto.getRepeatPassword())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.AUTH_PASSWORDS_MISMATCH);
        }

        if (!passwordEncoder.matches(currentPassword, actor.getPassword())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.AUTH_CURRENT_PASSWORD_INVALID);
        }

        if (passwordEncoder.matches(newPassword, actor.getPassword())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.AUTH_NEW_PASSWORD_SAME_AS_CURRENT);
        }

        actor.setPassword(passwordEncoder.encode(newPassword));
        actor.setPasswordChangedAt(LocalDateTime.now());
        userRepository.save(actor);
    }

    private void validateUsername(String username, Integer currentUserId) {
        if (username == null || !USERNAME_PATTERN.matcher(username).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.USERS_USERNAME_INVALID);
        }
        if (RESERVED_USERNAMES.contains(username)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.USERS_USERNAME_RESERVED);
        }
        Optional<User> existing = userRepository.findByUsername(username);
        if (existing.isPresent() && !existing.get().getId().equals(currentUserId)) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.USERS_USERNAME_TAKEN);
        }
        // Reject usernames that another (still active) user used in the past. History rows whose
        // owner is soft-deleted are hidden by @SQLRestriction on User, so released slots fall
        // through to "available".
        usernameHistoryRepository.findByOldUsername(username).ifPresent(history -> {
            Integer ownerId = history.getUser().getId();
            if (!ownerId.equals(currentUserId)) {
                throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.USERS_USERNAME_TAKEN);
            }
        });
    }
}
