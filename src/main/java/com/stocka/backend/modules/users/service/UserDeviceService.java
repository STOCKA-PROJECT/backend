package com.stocka.backend.modules.users.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.stocka.backend.modules.auth.entity.RefreshToken.RevocationReason;
import com.stocka.backend.modules.auth.repository.RefreshTokenRepository;
import com.stocka.backend.modules.common.error.ApiException;
import com.stocka.backend.modules.common.error.ErrorCodes;
import com.stocka.backend.modules.security.audit.SecurityAuditService;
import com.stocka.backend.modules.security.audit.SecurityEventType;
import com.stocka.backend.modules.users.entity.User;
import com.stocka.backend.modules.users.entity.UserDevice;
import com.stocka.backend.modules.users.repository.UserDeviceRepository;

/**
 * Coordinates {@link UserDevice} CRUD and links it to the refresh-token
 * family. A device equals a session: login creates a new family + a new
 * device; rotation touches an existing device; logout / revocation flips
 * both the device and the family to "revoked".
 */
@Service
public class UserDeviceService {

    private static final int DISPLAY_NAME_MAX_LENGTH = 120;

    private final UserDeviceRepository repository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserAgentParser userAgentParser;
    private final SecurityAuditService securityAuditService;

    public UserDeviceService(
            UserDeviceRepository repository,
            RefreshTokenRepository refreshTokenRepository,
            UserAgentParser userAgentParser,
            SecurityAuditService securityAuditService) {
        this.repository = repository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.userAgentParser = userAgentParser;
        this.securityAuditService = securityAuditService;
    }

    /**
     * Records a new device on login. Emits a {@code NEW_DEVICE_LOGIN} audit
     * entry — every login produces one, since each login starts a fresh
     * family.
     *
     * @param user   authenticated user
     * @param familyId refresh-token family for the new session
     * @param userAgent caller's UA (used to derive the display name)
     * @param ip caller's IP at login time
     * @return the persisted device
     */
    @Transactional
    public UserDevice registerForLogin(User user, String familyId, String userAgent, String ip) {
        LocalDateTime now = LocalDateTime.now();
        UserDevice device = new UserDevice()
                .setUser(user)
                .setFamilyId(familyId)
                .setDisplayName(userAgentParser.buildDisplayName(userAgent))
                .setUserAgentRaw(userAgent)
                .setLastIp(ip)
                .setFirstSeenAt(now)
                .setLastSeenAt(now);
        UserDevice saved = repository.save(device);
        securityAuditService.recordSuccess(SecurityEventType.NEW_DEVICE_LOGIN, user,
                "{\"familyId\":\"" + familyId + "\",\"deviceId\":" + saved.getId() + "}");
        return saved;
    }

    /**
     * Updates the {@code lastSeenAt} + {@code lastIp} on every successful
     * refresh. No-op if the device row was cleaned up (e.g. the family was
     * revoked); the refresh will be rejected on the way out anyway.
     */
    @Transactional
    public void touchOnRefresh(String familyId, String ip) {
        repository.findByFamilyId(familyId).ifPresent(device -> {
            if (device.getRevokedAt() != null) return;
            device.setLastSeenAt(LocalDateTime.now()).setLastIp(ip);
            repository.save(device);
        });
    }

    /**
     * Lists the current user's active sessions, newest first.
     *
     * @param user the requester
     * @return active devices
     */
    public List<UserDevice> listActive(User user) {
        return repository.findByUserAndRevokedAtIsNullOrderByLastSeenAtDesc(user);
    }

    /**
     * Marks a device revoked and wipes its refresh-token family.
     *
     * @param user owner of the device
     * @param deviceId device id from the panel
     * @throws ApiException 404 if the device doesn't belong to the user
     */
    @Transactional
    public void revoke(User user, Long deviceId) {
        UserDevice device = repository.findByIdAndUser(deviceId, user)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.SESSIONS_NOT_FOUND));
        if (device.getRevokedAt() != null) return;
        LocalDateTime now = LocalDateTime.now();
        device.setRevokedAt(now);
        repository.save(device);
        refreshTokenRepository.revokeFamily(device.getFamilyId(), RevocationReason.ADMIN_REVOKED, now);
        securityAuditService.recordSuccess(SecurityEventType.SESSION_REVOKED, user,
                "{\"familyId\":\"" + device.getFamilyId() + "\",\"deviceId\":" + device.getId() + "}");
    }

    /**
     * Bulk-revokes every device of the user except the one bound to {@code
     * currentFamilyId}. Used by "log out everywhere else".
     *
     * @param user the requester
     * @param currentFamilyId family of the current session (may be {@code null} to revoke all)
     * @return how many devices were revoked
     */
    @Transactional
    public int revokeAllExceptCurrent(User user, String currentFamilyId) {
        List<UserDevice> active = repository.findByUserAndRevokedAtIsNullOrderByLastSeenAtDesc(user);
        LocalDateTime now = LocalDateTime.now();
        int revoked = 0;
        for (UserDevice device : active) {
            if (currentFamilyId != null && currentFamilyId.equals(device.getFamilyId())) continue;
            device.setRevokedAt(now);
            repository.save(device);
            refreshTokenRepository.revokeFamily(device.getFamilyId(), RevocationReason.ADMIN_REVOKED, now);
            revoked++;
        }
        if (revoked > 0) {
            securityAuditService.recordSuccess(SecurityEventType.SESSION_REVOKED, user,
                    "{\"bulkRevoked\":" + revoked + "}");
        }
        return revoked;
    }

    /**
     * Renames a device. Trims to {@value #DISPLAY_NAME_MAX_LENGTH} characters.
     *
     * @throws ApiException 404 if the device doesn't belong to the user
     */
    @Transactional
    public UserDevice rename(User user, Long deviceId, String newDisplayName) {
        if (newDisplayName == null || newDisplayName.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.SESSIONS_NAME_INVALID);
        }
        UserDevice device = repository.findByIdAndUser(deviceId, user)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.SESSIONS_NOT_FOUND));
        String trimmed = newDisplayName.trim();
        if (trimmed.length() > DISPLAY_NAME_MAX_LENGTH) trimmed = trimmed.substring(0, DISPLAY_NAME_MAX_LENGTH);
        device.setDisplayName(trimmed);
        return repository.save(device);
    }

    /**
     * @return the device bound to the given family, when it still exists
     */
    public Optional<UserDevice> findByFamilyId(String familyId) {
        return repository.findByFamilyId(familyId);
    }
}
