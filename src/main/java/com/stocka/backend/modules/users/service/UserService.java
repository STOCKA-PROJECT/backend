package com.stocka.backend.modules.users.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.stocka.backend.modules.users.dto.UpdateUserProfileDto;
import com.stocka.backend.modules.users.entity.User;
import com.stocka.backend.modules.users.repository.UserRepository;

@Service
public class UserService {
    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public List<User> allUsers() {
        List<User> users = new ArrayList<>();
        userRepository.findAll().forEach(users::add);
        return users;
    }

    public void softDeleteCurrentUser(User user) {
        user.setDeletedAt(LocalDateTime.now());
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

        return userRepository.save(actor);
    }
}
