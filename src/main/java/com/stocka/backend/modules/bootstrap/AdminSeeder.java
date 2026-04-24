package com.stocka.backend.modules.bootstrap;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.stocka.backend.modules.auth.dto.RegisterUserDto;
import com.stocka.backend.modules.roles.entity.Role;
import com.stocka.backend.modules.roles.entity.RoleEnum;
import com.stocka.backend.modules.roles.repository.RoleRepository;
import com.stocka.backend.modules.users.entity.User;
import com.stocka.backend.modules.users.repository.UserRepository;

@Component
@Order(2)
public class AdminSeeder implements ApplicationListener<ContextRefreshedEvent> {
    private static final Logger log = LoggerFactory.getLogger(AdminSeeder.class);
    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AdminSeeder(
            RoleRepository roleRepository,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder
    ) {
        this.roleRepository = roleRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        log.info("Admin seeder iniciado");
        createOwnerAdministrator();
        log.info("Admin seeder finalizado");
    }

    private void createOwnerAdministrator() {
        RegisterUserDto userDto = new RegisterUserDto()
                .setUsername("joanmartorellcoll")
                .setName("Joan")
                .setLastName("Martorell Coll")
                .setEmail("joanmartorellcoll03@gmail.com")
                .setPassword("12345678")
                .setRepeatPassword("12345678");

        Optional<Role> optionalRole = roleRepository.findByName(RoleEnum.ADMIN);
        Optional<User> optionalUser = userRepository.findByEmail(userDto.getEmail());

        if (optionalRole.isEmpty()) {
            log.info("No se crea owner admin porque no existe el rol OWNER");
            return;
        }

        if (optionalUser.isPresent()) {
            log.info("Owner admin ya existe, se omite: {}", userDto.getEmail());
            return;
        }

        User user = new User()
                .setName(userDto.getName())
                .setLastName(userDto.getLastName())
                .setUsername(userDto.getUsername())
                .setEmail(userDto.getEmail())
                .setPassword(passwordEncoder.encode(userDto.getPassword()))
                .setRole(optionalRole.get())
                .setEmailVerified(true);

        userRepository.save(user);
        log.info("Owner admin creado: {}", userDto.getEmail());
    }
}
