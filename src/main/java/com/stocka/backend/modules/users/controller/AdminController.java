package com.stocka.backend.modules.users.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.stocka.backend.modules.auth.dto.RegisterUserDto;
import com.stocka.backend.modules.users.entity.User;
import com.stocka.backend.modules.users.service.UserService;

@RequestMapping("/admins")
@RestController
public class AdminController {
    private final UserService userService;

    public AdminController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<User> createAdmin(@RequestBody RegisterUserDto registerUserDto) {
        User createdAdmin = userService.createAdmin(registerUserDto);
        return ResponseEntity.ok(createdAdmin);
    }
}
