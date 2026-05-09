package com.stocka.backend.modules.bootstrap;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.stocka.backend.modules.roles.entity.Role;
import com.stocka.backend.modules.roles.entity.RoleEnum;
import com.stocka.backend.modules.roles.repository.RoleRepository;
import com.stocka.backend.modules.users.entity.Language;
import com.stocka.backend.modules.users.entity.User;
import com.stocka.backend.modules.users.repository.UserRepository;

@Component
@Order(2)
public class AdminSeeder implements ApplicationListener<ContextRefreshedEvent> {
    private static final Logger log = LoggerFactory.getLogger(AdminSeeder.class);

    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String bootstrapEmail;
    private final String bootstrapPassword;

    public AdminSeeder(
            RoleRepository roleRepository,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            @Value("${app.admin.bootstrap.email:}") String bootstrapEmail,
            @Value("${app.admin.bootstrap.password:}") String bootstrapPassword
    ) {
        this.roleRepository = roleRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.bootstrapEmail = bootstrapEmail;
        this.bootstrapPassword = bootstrapPassword;
    }

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        log.info("Admin seeder iniciado");
        createOwnerAdministrator();
        log.info("Admin seeder finalizado");
    }

    private void createOwnerAdministrator() {
        if (bootstrapEmail == null || bootstrapEmail.isBlank()
                || bootstrapPassword == null || bootstrapPassword.isBlank()) {
            log.warn("Admin seeder omitido: ADMIN_BOOTSTRAP_EMAIL o ADMIN_BOOTSTRAP_PASSWORD no están definidos");
            return;
        }

        Optional<Role> optionalRole = roleRepository.findByName(RoleEnum.ADMIN);
        if (optionalRole.isEmpty()) {
            log.warn("Admin seeder omitido: no existe el rol ADMIN");
            return;
        }

        Optional<User> optionalUser = userRepository.findByEmail(bootstrapEmail);
        if (optionalUser.isPresent()) {
            log.info("Owner admin ya existe, se omite: {}", bootstrapEmail);
            return;
        }

        String localPart = bootstrapEmail.contains("@")
                ? bootstrapEmail.substring(0, bootstrapEmail.indexOf('@'))
                : bootstrapEmail;

        User user = new User()
                .setName("Admin")
                .setLastName("Bootstrap")
                .setUsername(localPart)
                .setEmail(bootstrapEmail)
                .setPassword(passwordEncoder.encode(bootstrapPassword))
                .setRole(optionalRole.get())
                .setEmailVerified(false)
                .setMustChangePassword(true)
                .setLanguage(Language.ES);

        userRepository.save(user);
        log.info("Owner admin creado: {}", bootstrapEmail);
    }
}
