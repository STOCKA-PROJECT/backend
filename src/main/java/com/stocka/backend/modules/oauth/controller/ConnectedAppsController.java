package com.stocka.backend.modules.oauth.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.stocka.backend.modules.oauth.dto.ConnectedAppDto;
import com.stocka.backend.modules.oauth.service.ConnectedAppService;
import com.stocka.backend.modules.users.entity.User;

/**
 * "Connected apps" of the current user: which MCP connectors hold a grant and revoking them.
 * Served on the regular REST chain (SPA token), not on the authorization server chain.
 */
@RestController
@RequestMapping("/users/me/connected-apps")
public class ConnectedAppsController {

    private final ConnectedAppService connectedApps;

    public ConnectedAppsController(ConnectedAppService connectedApps) {
        this.connectedApps = connectedApps;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<ConnectedAppDto>> list(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(connectedApps.list(user));
    }

    /**
     * Revokes a client. The client id travels as a query parameter because Client ID Metadata
     * Document ids are URLs.
     */
    @DeleteMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> revoke(@AuthenticationPrincipal User user, @RequestParam("clientId") String clientId) {
        connectedApps.revoke(user, clientId);
        return ResponseEntity.noContent().build();
    }
}
