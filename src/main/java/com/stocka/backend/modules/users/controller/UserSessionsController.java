package com.stocka.backend.modules.users.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.stocka.backend.modules.security.service.JwtService;
import com.stocka.backend.modules.users.dto.RenameSessionRequestDto;
import com.stocka.backend.modules.users.dto.SessionResponseDto;
import com.stocka.backend.modules.users.entity.User;
import com.stocka.backend.modules.users.entity.UserDevice;
import com.stocka.backend.modules.users.service.UserDeviceService;

import jakarta.validation.Valid;

/**
 * Endpoints powering the "Connected devices" section in the account screen
 * (Feature 6). The {@code current} flag is computed from the {@code familyId}
 * claim carried in the requesting access token — that lets the panel tell
 * apart "this browser" from the other sessions without reading the
 * path-restricted refresh cookie.
 */
@RestController
@RequestMapping("/users/me/sessions")
public class UserSessionsController {

    private final UserDeviceService userDeviceService;
    private final JwtService jwtService;

    public UserSessionsController(UserDeviceService userDeviceService, JwtService jwtService) {
        this.userDeviceService = userDeviceService;
        this.jwtService = jwtService;
    }

    /**
     * Returns the user's active sessions, newest first.
     *
     * @param authHeader incoming Authorization header — used to recover the
     *                   current session's familyId
     * @return one row per active device
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<SessionResponseDto>> listMySessions(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        User user = currentUser();
        String currentFamilyId = familyIdFromHeader(authHeader);
        List<SessionResponseDto> body = userDeviceService.listActive(user).stream()
                .map(device -> SessionResponseDto.from(device, isCurrent(device, currentFamilyId)))
                .toList();
        return ResponseEntity.ok(body);
    }

    /**
     * Revokes a single session. Bound refresh-token family is wiped, so all
     * future {@code /auth/refresh} from that device fail.
     */
    @DeleteMapping("/{deviceId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> revoke(@PathVariable Long deviceId) {
        userDeviceService.revoke(currentUser(), deviceId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Revokes every active session except the one this request belongs to.
     */
    @DeleteMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> revokeAllExceptCurrent(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        userDeviceService.revokeAllExceptCurrent(currentUser(), familyIdFromHeader(authHeader));
        return ResponseEntity.noContent().build();
    }

    /**
     * Renames a session.
     */
    @PatchMapping("/{deviceId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<SessionResponseDto> rename(
            @PathVariable Long deviceId,
            @Valid @RequestBody RenameSessionRequestDto dto,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        UserDevice updated = userDeviceService.rename(currentUser(), deviceId, dto.getDisplayName());
        return ResponseEntity.ok(SessionResponseDto.from(updated, isCurrent(updated, familyIdFromHeader(authHeader))));
    }

    private static User currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return (User) authentication.getPrincipal();
    }

    private String familyIdFromHeader(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return null;
        try {
            return jwtService.extractFamilyId(authHeader.substring(7));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static boolean isCurrent(UserDevice device, String currentFamilyId) {
        return currentFamilyId != null && currentFamilyId.equals(device.getFamilyId());
    }
}
