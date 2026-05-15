package com.stocka.backend.modules.notifications.preferences.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.stocka.backend.modules.notifications.preferences.dto.NotificationPreferenceResponseDto;
import com.stocka.backend.modules.notifications.preferences.dto.UpdateNotificationPreferenceDto;
import com.stocka.backend.modules.notifications.preferences.service.NotificationPreferenceService;
import com.stocka.backend.modules.users.entity.User;

/**
 * Exposes the caller's per-organization email-notification preferences. The endpoint
 * never lets a user read or write someone else's preferences; the authenticated principal
 * is the only valid identity here.
 */
@RestController
@RequestMapping("/users/me/notification-preferences")
public class NotificationPreferenceController {

    private final NotificationPreferenceService preferenceService;

    public NotificationPreferenceController(NotificationPreferenceService preferenceService) {
        this.preferenceService = preferenceService;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<NotificationPreferenceResponseDto>> listMine() {
        User actor = currentUser();
        return ResponseEntity.ok(preferenceService.listForUser(actor));
    }

    @PutMapping("/{organizationId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<NotificationPreferenceResponseDto> upsertMine(
            @PathVariable Integer organizationId,
            @RequestBody UpdateNotificationPreferenceDto dto
    ) {
        User actor = currentUser();
        return ResponseEntity.ok(preferenceService.upsert(actor, organizationId, dto));
    }

    private static User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (User) auth.getPrincipal();
    }
}
