package com.stocka.backend.modules.users.controller;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.stocka.backend.modules.security.entity.SecurityAuditEntry;
import com.stocka.backend.modules.security.repository.SecurityAuditEntryRepository;
import com.stocka.backend.modules.users.dto.ChangePasswordDto;
import com.stocka.backend.modules.users.dto.SecurityActivityResponseDto;
import com.stocka.backend.modules.users.dto.UpdateUserProfileDto;
import com.stocka.backend.modules.users.dto.UserResponseDto;
import com.stocka.backend.modules.users.entity.User;
import com.stocka.backend.modules.users.service.UserService;

import jakarta.validation.Valid;

@RequestMapping("/users")
@RestController
public class UserController {

    /** Hard cap on {@code GET /users/me/security/activity} page size. */
    private static final int SECURITY_ACTIVITY_MAX_PAGE_SIZE = 100;

    private final UserService userService;
    private final SecurityAuditEntryRepository securityAuditRepository;

    public UserController(UserService userService, SecurityAuditEntryRepository securityAuditRepository) {
        this.userService = userService;
        this.securityAuditRepository = securityAuditRepository;
    }

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<UserResponseDto> authenticatedUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        User currentUser = (User) authentication.getPrincipal();
        return ResponseEntity.ok(UserResponseDto.from(currentUser));
    }

    @PatchMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<UserResponseDto> updateMyProfile(@RequestBody UpdateUserProfileDto dto) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        User currentUser = (User) authentication.getPrincipal();
        User updated = userService.updateProfile(currentUser, dto);
        return ResponseEntity.ok(UserResponseDto.from(updated));
    }

    @PatchMapping("/me/password")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> changeMyPassword(@Valid @RequestBody ChangePasswordDto dto) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        User currentUser = (User) authentication.getPrincipal();
        userService.changePassword(currentUser, dto);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> deleteCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        User currentUser = (User) authentication.getPrincipal();
        userService.softDeleteCurrentUser(currentUser);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<UserResponseDto>> allUsers() {
        List<UserResponseDto> users = userService.allUsers().stream()
                .map(UserResponseDto::from)
                .toList();
        return ResponseEntity.ok(users);
    }

    /**
     * Returns the current user's security audit trail, newest first.
     *
     * @param page zero-based page index
     * @param size page size (clamped to {@value #SECURITY_ACTIVITY_MAX_PAGE_SIZE})
     * @return paginated audit entries
     */
    @GetMapping("/me/security/activity")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Page<SecurityActivityResponseDto>> mySecurityActivity(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        User currentUser = (User) authentication.getPrincipal();
        int effectiveSize = Math.min(Math.max(size, 1), SECURITY_ACTIVITY_MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(Math.max(page, 0), effectiveSize);
        Page<SecurityAuditEntry> entries = securityAuditRepository
                .findByUserOrderByCreatedAtDesc(currentUser, pageable);
        return ResponseEntity.ok(entries.map(SecurityActivityResponseDto::from));
    }
}
