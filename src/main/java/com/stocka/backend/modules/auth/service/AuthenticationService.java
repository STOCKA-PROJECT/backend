package com.stocka.backend.modules.auth.service;

import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.stocka.backend.modules.auth.dto.LoginUserDto;
import com.stocka.backend.modules.auth.dto.RegisterUserDto;
import com.stocka.backend.modules.common.dto.AvailabilityResponse;
import com.stocka.backend.modules.common.dto.AvailabilityResponse.Reason;
import com.stocka.backend.modules.roles.entity.Role;
import com.stocka.backend.modules.roles.entity.RoleEnum;
import com.stocka.backend.modules.roles.repository.RoleRepository;
import com.stocka.backend.modules.security.entity.InvalidatedToken;
import com.stocka.backend.modules.security.repository.InvalidatedTokenRepository;
import com.stocka.backend.modules.security.service.JwtService;
import com.stocka.backend.modules.users.entity.Language;
import com.stocka.backend.modules.users.entity.User;
import com.stocka.backend.modules.users.repository.UserRepository;

@Service
public class AuthenticationService {

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-z0-9]{3,24}$");

    private static final Set<String> RESERVED_USERNAMES = Set.of(
            "admin", "api", "root", "support", "system", "stocka",
            "auth", "users", "www", "app", "health",
            "null", "undefined"
    );

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final InvalidatedTokenRepository invalidatedTokenRepository;
    private final JwtService jwtService;

    public AuthenticationService(
            UserRepository userRepository,
            RoleRepository roleRepository,
            PasswordEncoder passwordEncoder,
            AuthenticationManager authenticationManager,
            InvalidatedTokenRepository invalidatedTokenRepository,
            JwtService jwtService
    ) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.invalidatedTokenRepository = invalidatedTokenRepository;
        this.jwtService = jwtService;
    }

    public User signup(RegisterUserDto input) {
        if (!input.getPassword().equals(input.getRepeatPassword())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Las contraseñas no coinciden");
        }

        Language language = Language.fromString(input.getLanguage());

        if (userRepository.findByEmail(input.getEmail()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ya existe un usuario con ese email");
        }

        AvailabilityResponse usernameCheck = checkUsernameAvailability(input.getUsername());
        if (!usernameCheck.available()) {
            switch (usernameCheck.reason()) {
                case INVALID_FORMAT -> throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "El nombre de usuario no es válido");
                case RESERVED -> throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Ese nombre de usuario está reservado");
                case TAKEN -> throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "Ya existe un usuario con ese username");
            }
        }

        Optional<Role> optionalRole = roleRepository.findByName(RoleEnum.USER);

        if (optionalRole.isEmpty()) {
            throw new IllegalStateException("Role USER no existe en la base de datos");
        }

        User user = new User()
                .setName(input.getName())
                .setLastName(input.getLastName())
                .setUsername(input.getUsername())
                .setEmail(input.getEmail())
                .setPassword(passwordEncoder.encode(input.getPassword()))
                .setRole(optionalRole.get())
                .setEmailVerified(true)
                .setLanguage(language);

        return userRepository.save(user);
    }

    /**
     * Checks whether the given username can be used to register a new user.
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
        return AvailabilityResponse.ok();
    }

    public void logout(String token) {
        invalidatedTokenRepository.deleteExpired(java.time.LocalDateTime.now());
        InvalidatedToken invalidatedToken = new InvalidatedToken()
                .setToken(token)
                .setExpiresAt(jwtService.extractExpirationAsLocalDateTime(token));
        invalidatedTokenRepository.save(invalidatedToken);
    }

    public User authenticate(LoginUserDto input) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(input.getEmail(), input.getPassword())
        );
        User user = (User) authentication.getPrincipal();
        if (!user.isEmailVerified()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "El email no ha sido verificado");
        }
        return user;
    }
}
