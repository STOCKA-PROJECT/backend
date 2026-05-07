package com.stocka.backend.modules.users.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.stocka.backend.modules.common.error.ApiException;
import com.stocka.backend.modules.common.error.ErrorCodes;
import com.stocka.backend.modules.organizations.entity.OrganizationMember;
import com.stocka.backend.modules.organizations.repository.OrganizationMemberRepository;
import com.stocka.backend.modules.users.dto.ChangePasswordDto;
import com.stocka.backend.modules.users.dto.UpdateUserProfileDto;
import com.stocka.backend.modules.users.entity.Language;
import com.stocka.backend.modules.users.entity.User;
import com.stocka.backend.modules.users.repository.UserRepository;

@Service
public class UserService {

    private static final int MIN_PASSWORD_LENGTH = 8;

    private final UserRepository userRepository;
    private final OrganizationMemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(
            UserRepository userRepository,
            OrganizationMemberRepository memberRepository,
            PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.memberRepository = memberRepository;
        this.passwordEncoder = passwordEncoder;
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
        user.setDeletedAt(now);
        userRepository.save(user);
    }

    public User updateProfile(User actor, UpdateUserProfileDto dto) {
        if (dto.getEmail() != null && !dto.getEmail().equals(actor.getEmail())) {
            Optional<User> existing = userRepository.findByEmail(dto.getEmail());
            if (existing.isPresent() && !existing.get().getId().equals(actor.getId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Ya existe un usuario con ese email");
            }
            actor.setEmail(dto.getEmail());
            actor.setEmailVerified(false);
        }

        if (dto.getUsername() != null && !dto.getUsername().equals(actor.getUsernameValue())) {
            Optional<User> existing = userRepository.findByUsername(dto.getUsername());
            if (existing.isPresent() && !existing.get().getId().equals(actor.getId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Ya existe un usuario con ese username");
            }
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

        return userRepository.save(actor);
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
}
