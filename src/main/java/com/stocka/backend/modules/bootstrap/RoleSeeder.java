package com.stocka.backend.modules.bootstrap;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.stocka.backend.modules.roles.entity.Role;
import com.stocka.backend.modules.roles.entity.RoleEnum;
import com.stocka.backend.modules.roles.repository.RoleRepository;

@Component
@Order(1)
public class RoleSeeder implements ApplicationListener<ContextRefreshedEvent> {
    private static final Logger log = LoggerFactory.getLogger(RoleSeeder.class);
    private final RoleRepository roleRepository;

    public RoleSeeder(RoleRepository roleRepository) {
        this.roleRepository = roleRepository;
    }

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        log.info("Role seeder iniciado");
        loadRoles();
        log.info("Role seeder finalizado");
    }

    private void loadRoles() {
        RoleEnum[] roleNames = new RoleEnum[]{RoleEnum.USER, RoleEnum.ADMIN};
        Map<RoleEnum, String> roleDescriptionMap = Map.of(
                RoleEnum.USER, "can access the authenticated user details for default user",
                RoleEnum.ADMIN, "has access to all endpoints, including creating admin users"
        );

        Arrays.stream(roleNames).forEach(roleName -> {
            Optional<Role> optionalRole = roleRepository.findByName(roleName);

            if (optionalRole.isEmpty()) {
                Role roleToCreate = new Role()
                        .setName(roleName)
                        .setDescription(roleDescriptionMap.get(roleName));
                roleRepository.save(roleToCreate);
                log.info("Rol creado: {}", roleName);
            } else {
                log.info("Rol ya existe, se omite: {}", roleName);
            }
        });
    }
}
