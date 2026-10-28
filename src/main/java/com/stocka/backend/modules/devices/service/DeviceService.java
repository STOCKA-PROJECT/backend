package com.stocka.backend.modules.devices.service;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.stocka.backend.modules.common.error.ApiException;
import com.stocka.backend.modules.common.error.ErrorCodes;
import com.stocka.backend.modules.devices.config.DevicesProperties;
import com.stocka.backend.modules.devices.dto.DeviceResponseDto;
import com.stocka.backend.modules.devices.dto.HeartbeatDto;
import com.stocka.backend.modules.devices.entity.Device;
import com.stocka.backend.modules.devices.repository.DeviceRepository;
import com.stocka.backend.modules.devices.service.DeviceTokenService.GeneratedToken;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.service.OrganizationService;
import com.stocka.backend.modules.users.entity.User;

/**
 * Registration and telemetry of the belén players ({@link Device}) of an organization.
 *
 * <p>Users register a device with a name (unique per organization, 1–{@value #MAX_NAME_LENGTH}
 * characters) and receive a heartbeat token exactly once; only its sha256 is stored. The player then
 * posts heartbeats authenticated with that token ({@link #recordHeartbeat}); the latest report is kept
 * on the row and a device counts as online while that report is younger than
 * {@code stocka.devices.online-window}. Rotating the token invalidates the previous one immediately.
 *
 * <p>A device may also carry where the editor reaches it on the local network ({@code localUrl},
 * normalized like the editor's {@code normalizePiUrl}: {@code scheme://host[:port]}, port
 * {@value #DEFAULT_PLAYER_PORT} for plain http) and its {@value #PIN_LENGTH}-digit PIN, so every computer
 * of the organization finds it configured. Managers set them from the dashboard; whoever may send shows
 * can update them from the editor ({@link #updateConnection}).
 *
 * <p>On soft-delete the name is mangled with a "::deleted::ID" suffix so the
 * {@code uk_device_org_name} slot is freed, like ports.
 */
@Service
public class DeviceService {
    /** Maximum length of a device name, matching the {@code name VARCHAR(80)} column. */
    public static final int MAX_NAME_LENGTH = 80;
    /** Authorization scheme the player uses: {@code Authorization: Device <token>}. */
    public static final String AUTH_SCHEME = "Device";
    /** Port of the player's local API when the address names none (rpi4-system {@code app/api}). */
    public static final int DEFAULT_PLAYER_PORT = 8080;
    /** Length of the player's PIN (rpi4-system {@code app/device.py}). */
    public static final int PIN_LENGTH = 6;
    /** Maximum length of the local address, matching the {@code local_url VARCHAR(255)} column. */
    public static final int MAX_LOCAL_URL_LENGTH = 255;

    private static final Logger log = LoggerFactory.getLogger(DeviceService.class);
    private static final String SOFT_DELETE_SUFFIX = "::deleted::";
    private static final int MAX_IP_LENGTH = 45;
    private static final int MAX_DEVICE_ID_LENGTH = 36;
    private static final Pattern PIN_PATTERN = Pattern.compile("\\d{" + PIN_LENGTH + "}");
    private static final Pattern SCHEME_PATTERN = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*://.*");

    private final OrganizationService organizationService;
    private final DeviceRepository deviceRepository;
    private final DeviceTokenService tokenService;
    private final DeviceOverridesJsonCodec overridesCodec;
    private final DevicesProperties properties;
    private final Clock clock;

    public DeviceService(
            OrganizationService organizationService,
            DeviceRepository deviceRepository,
            DeviceTokenService tokenService,
            DeviceOverridesJsonCodec overridesCodec,
            DevicesProperties properties,
            Clock clock
    ) {
        this.organizationService = organizationService;
        this.deviceRepository = deviceRepository;
        this.tokenService = tokenService;
        this.overridesCodec = overridesCodec;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * A device together with a raw token that is valid for it right now.
     *
     * @param device the persisted device
     * @param token  raw heartbeat token, to be shown once
     */
    public record DeviceWithToken(Device device, String token) { }

    /**
     * Lists the devices of an organization ordered by name.
     *
     * @param orgId organization id
     * @return ordered active devices
     */
    public List<Device> listAll(Integer orgId) {
        Organization org = organizationService.findById(orgId);
        return deviceRepository.findByOrganizationOrderByNameAscIdAsc(org);
    }

    /**
     * Resolves a device that must belong to the given organization.
     *
     * @param orgId organization id
     * @param id    device row id
     * @return the matching device
     * @throws ApiException 404 ({@link ErrorCodes#DEVICES_NOT_FOUND}) when missing or in another org
     */
    public Device findInOrg(Integer orgId, Integer id) {
        Organization org = organizationService.findById(orgId);
        Device device = deviceRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.DEVICES_NOT_FOUND));
        if (!device.getOrganization().getId().equals(org.getId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.DEVICES_NOT_FOUND);
        }
        return device;
    }

    /**
     * Registers a new device and issues its first heartbeat token. The creator is the authenticated
     * user, when there is one in the security context.
     *
     * @param orgId       organization id
     * @param rawName     requested name (trimmed here)
     * @param rawLocalUrl address on the local network, or {@code null}/blank for none
     * @param rawPin      the player's PIN, or {@code null}/blank for none
     * @return the persisted device and its raw token
     * @throws ApiException 400 when the name is blank or too long, the address is not an http(s) URL
     *                      ({@link ErrorCodes#DEVICES_LOCAL_URL_INVALID}) or the PIN is not
     *                      {@value #PIN_LENGTH} digits ({@link ErrorCodes#DEVICES_PIN_INVALID}); 409
     *                      ({@link ErrorCodes#DEVICES_NAME_TAKEN}) when another device has the name
     */
    @Transactional
    public DeviceWithToken create(Integer orgId, String rawName, String rawLocalUrl, String rawPin) {
        Organization org = organizationService.findById(orgId);
        String name = sanitizeName(rawName);
        String localUrl = normalizeLocalUrl(rawLocalUrl);
        String pin = sanitizePin(rawPin);
        ensureUniqueName(org, name, null);
        GeneratedToken token = tokenService.generate();

        Device device = new Device()
                .setOrganization(org)
                .setName(name)
                .setBoard(Device.DEFAULT_BOARD)
                .setLocalUrl(localUrl)
                .setPin(pin)
                .setTokenHash(token.hash())
                .setTokenPrefix(token.prefix())
                .setCreatedBy(currentUserId());
        return new DeviceWithToken(saveAndFlush(device), token.token());
    }

    /**
     * Updates the fields a manager edits from the dashboard. A {@code null} argument leaves its field as
     * it is; a blank address or PIN removes it. The name follows the rules of {@link #create}.
     *
     * @param orgId       organization id
     * @param id          device row id
     * @param rawName     new name (trimmed here), or {@code null}
     * @param rawLocalUrl new address, blank to remove it, or {@code null}
     * @param rawPin      new PIN, blank to remove it, or {@code null}
     * @return the persisted device
     * @throws ApiException the same codes as {@link #create}, and 404 when the device is not found
     */
    @Transactional
    public Device update(Integer orgId, Integer id, String rawName, String rawLocalUrl, String rawPin) {
        Device device = findInOrg(orgId, id);
        if (rawName != null) {
            String name = sanitizeName(rawName);
            if (!name.equals(device.getName())) {
                ensureUniqueName(device.getOrganization(), name, device.getId());
                device.setName(name);
            }
        }
        applyConnection(device, rawLocalUrl, rawPin);
        return saveAndFlush(device);
    }

    /**
     * Saves what the timeline editor learnt reaching the player on the local network: the address it
     * answered at, the PIN it accepted and the uuid it reported. {@code null} leaves a field as it is; a
     * blank address or PIN removes it; a blank uuid is ignored. A uuid different from the bound one
     * replaces it (the card may have been re-imaged), logged like in {@link #recordHeartbeat}.
     *
     * @param orgId       organization id
     * @param id          device row id
     * @param rawLocalUrl address, blank to remove it, or {@code null}
     * @param rawPin      PIN, blank to remove it, or {@code null}
     * @param rawDeviceId uuid of the player, or {@code null}
     * @return the persisted device
     * @throws ApiException 400 ({@link ErrorCodes#DEVICES_LOCAL_URL_INVALID},
     *                      {@link ErrorCodes#DEVICES_PIN_INVALID}) and 404 when the device is not found
     */
    @Transactional
    public Device updateConnection(Integer orgId, Integer id, String rawLocalUrl, String rawPin, String rawDeviceId) {
        Device device = findInOrg(orgId, id);
        applyConnection(device, rawLocalUrl, rawPin);
        String deviceId = rawDeviceId == null ? null : truncate(rawDeviceId.trim(), MAX_DEVICE_ID_LENGTH);
        if (deviceId != null && !deviceId.isEmpty() && !deviceId.equals(device.getDeviceId())) {
            if (device.getDeviceId() == null) {
                log.info("device_bound_from_editor id={} deviceId={}", device.getId(), deviceId);
            } else {
                log.warn("device_id_changed_from_editor id={} previous={} current={}", device.getId(), device.getDeviceId(), deviceId);
            }
            device.setDeviceId(deviceId);
        }
        return saveAndFlush(device);
    }

    /**
     * Soft-deletes a device, mangling its name so the UNIQUE slot is freed. Its token stops working
     * at once because heartbeats only find active rows.
     *
     * @param orgId organization id
     * @param id    device row id
     */
    @Transactional
    public void softDelete(Integer orgId, Integer id) {
        Device device = findInOrg(orgId, id);
        device.setName(buildSoftDeletedName(device.getName(), device.getId()));
        device.setDeletedAt(LocalDateTime.now(clock));
        deviceRepository.save(device);
    }

    /**
     * Replaces the heartbeat token of a device. The previous token is invalid as soon as this
     * returns.
     *
     * @param orgId organization id
     * @param id    device row id
     * @return the device and its new raw token
     */
    @Transactional
    public DeviceWithToken rotateToken(Integer orgId, Integer id) {
        Device device = findInOrg(orgId, id);
        GeneratedToken token = tokenService.generate();
        device.setTokenHash(token.hash());
        device.setTokenPrefix(token.prefix());
        return new DeviceWithToken(saveAndFlush(device), token.token());
    }

    /**
     * Stores a heartbeat sent by a player. The device is looked up by the hash of {@code rawToken};
     * the first heartbeat binds the player's uuid and a later, different uuid is accepted (the card
     * may have been re-imaged) but logged. Every {@code last*} field is replaced by the report.
     *
     * @param rawToken token presented in {@code Authorization: Device <token>}
     * @param dto      the report
     * @param clientIp public address the request came from (already resolved through trusted proxies)
     * @return the updated device
     * @throws ApiException 401 ({@link ErrorCodes#DEVICES_TOKEN_INVALID}) when no active device has
     *                      that token
     */
    @Transactional
    public Device recordHeartbeat(String rawToken, HeartbeatDto dto, String clientIp) {
        Device device = findByToken(rawToken)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.DEVICES_TOKEN_INVALID));

        if (device.getDeviceId() == null) {
            log.info("device_bound id={} deviceId={}", device.getId(), dto.deviceId());
        } else if (!device.getDeviceId().equals(dto.deviceId())) {
            log.warn("device_id_changed id={} previous={} current={}", device.getId(), device.getDeviceId(), dto.deviceId());
        }
        device.setDeviceId(dto.deviceId())
                .setLastState(dto.state())
                .setLastShowHash(dto.showHash())
                .setLastShowName(dto.showName())
                .setLastShowTimelineId(dto.showTimelineId())
                .setLastOverrides(overridesCodec.serialize(dto.overrides()))
                .setLastAlerts(overridesCodec.serializeAlerts(dto.alerts()))
                .setLastVersion(dto.version())
                .setLastUptimeS(dto.uptimeS())
                .setLastNetMode(dto.netMode())
                .setLastError(dto.error())
                .setLastIp(truncate(clientIp, MAX_IP_LENGTH))
                .setLastSeenAt(LocalDateTime.now(clock));
        return deviceRepository.save(device);
    }

    /**
     * Whether the device's last heartbeat is within the online window (inclusive).
     *
     * @param device device to evaluate
     * @return {@code true} when a heartbeat arrived at most {@code online-window} ago
     */
    public boolean isOnline(Device device) {
        LocalDateTime lastSeen = device.getLastSeenAt();
        if (lastSeen == null) {
            return false;
        }
        LocalDateTime threshold = LocalDateTime.now(clock).minus(properties.getOnlineWindow());
        return !lastSeen.isBefore(threshold);
    }

    /**
     * Builds the REST view of a device without its PIN.
     *
     * @param device source device
     * @return response DTO with {@code online} and the deserialized overrides
     */
    public DeviceResponseDto toResponse(Device device) {
        return toResponse(device, false);
    }

    /**
     * Builds the REST view of a device.
     *
     * @param device    source device
     * @param revealPin whether the caller may see the PIN (who may send shows)
     * @return response DTO with {@code online} and the deserialized overrides
     */
    public DeviceResponseDto toResponse(Device device, boolean revealPin) {
        return DeviceResponseDto.from(device, isOnline(device), overridesCodec.deserialize(device.getLastOverrides()),
                overridesCodec.deserializeAlerts(device.getLastAlerts()), revealPin);
    }

    /**
     * Normalizes the address of a player on the local network the way the editor does: a missing
     * scheme means {@code http://}, only {@code scheme://host[:port]} is kept and plain http without a
     * port gets {@value #DEFAULT_PLAYER_PORT}.
     *
     * @param raw address as typed ({@code belen.local}, {@code http://10.42.0.1:8080/}...)
     * @return the normalized address, or {@code null} when {@code raw} is {@code null} or blank
     * @throws ApiException 400 ({@link ErrorCodes#DEVICES_LOCAL_URL_INVALID}) when it is not an http(s)
     *                      address with a host, or the result is longer than {@value #MAX_LOCAL_URL_LENGTH}
     */
    public static String normalizeLocalUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        String withScheme = SCHEME_PATTERN.matcher(trimmed).matches() ? trimmed : "http://" + trimmed;
        URI uri;
        try {
            uri = new URI(withScheme);
        } catch (URISyntaxException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.DEVICES_LOCAL_URL_INVALID, null, ex);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        if ((!scheme.equals("http") && !scheme.equals("https")) || host == null || host.isEmpty() || uri.getUserInfo() != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.DEVICES_LOCAL_URL_INVALID);
        }
        int port = uri.getPort();
        if (port < 0 && scheme.equals("http")) {
            port = DEFAULT_PLAYER_PORT;
        }
        String normalized = scheme + "://" + host.toLowerCase(Locale.ROOT) + (port < 0 ? "" : ":" + port);
        if (normalized.length() > MAX_LOCAL_URL_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.DEVICES_LOCAL_URL_INVALID);
        }
        return normalized;
    }

    /**
     * The active device presenting {@code Authorization: Device <token>}.
     *
     * @param authorizationHeader header value; may be {@code null}
     * @return the device the token belongs to
     * @throws ApiException 401 ({@link ErrorCodes#DEVICES_TOKEN_INVALID}) when the header is missing,
     *                      uses another scheme or the token is unknown or revoked
     */
    @Transactional(readOnly = true)
    public Device authenticate(String authorizationHeader) {
        return extractDeviceToken(authorizationHeader)
                .flatMap(this::findByToken)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.DEVICES_TOKEN_INVALID));
    }

    /**
     * Extracts the raw token from an {@code Authorization} header using the {@value #AUTH_SCHEME}
     * scheme (case-insensitive). Any other scheme, including {@code Bearer}, yields empty so the
     * SPA/MCP tokens are never mistaken for device credentials.
     *
     * @param authorizationHeader header value; may be {@code null}
     * @return the token, or empty when the header is missing, uses another scheme or has no token
     */
    public static Optional<String> extractDeviceToken(String authorizationHeader) {
        if (authorizationHeader == null) {
            return Optional.empty();
        }
        String header = authorizationHeader.trim();
        String prefix = AUTH_SCHEME + " ";
        if (header.length() <= prefix.length() || !header.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return Optional.empty();
        }
        String token = header.substring(prefix.length()).trim();
        return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }

    private Optional<Device> findByToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        return deviceRepository.findByTokenHash(tokenService.hash(rawToken));
    }

    private void ensureUniqueName(Organization org, String name, Integer excludeId) {
        Optional<Device> existing = deviceRepository.findByOrganizationAndName(org, name);
        if (existing.isPresent() && (excludeId == null || !existing.get().getId().equals(excludeId))) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.DEVICES_NAME_TAKEN);
        }
    }

    private static void applyConnection(Device device, String rawLocalUrl, String rawPin) {
        if (rawLocalUrl != null) {
            device.setLocalUrl(normalizeLocalUrl(rawLocalUrl));
        }
        if (rawPin != null) {
            device.setPin(sanitizePin(rawPin));
        }
    }

    private static String sanitizePin(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        if (!PIN_PATTERN.matcher(trimmed).matches()) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("length", PIN_LENGTH);
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.DEVICES_PIN_INVALID, p);
        }
        return trimmed;
    }

    private static String sanitizeName(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.DEVICES_NAME_REQUIRED);
        }
        String trimmed = raw.trim();
        if (trimmed.length() > MAX_NAME_LENGTH) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("max", MAX_NAME_LENGTH);
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.DEVICES_NAME_TOO_LONG, p);
        }
        return trimmed;
    }

    /**
     * Builds the renamed value for a soft-deleted row so its original name is freed for a new active
     * row with the same name. The id-based suffix keeps the renamed value unique across all
     * previously soft-deleted rows; the base is truncated so the result still fits the column.
     */
    static String buildSoftDeletedName(String currentName, Integer id) {
        String suffix = SOFT_DELETE_SUFFIX + (id == null ? "?" : id);
        int maxBaseLen = Math.max(0, MAX_NAME_LENGTH - suffix.length());
        String base = currentName == null ? "" : currentName;
        if (base.length() > maxBaseLen) {
            base = base.substring(0, maxBaseLen);
        }
        return base + suffix;
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    private static Integer currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof User user)) {
            return null;
        }
        return user.getId();
    }

    private Device saveAndFlush(Device device) {
        try {
            return deviceRepository.saveAndFlush(device);
        } catch (DataIntegrityViolationException ex) {
            // Race backstop only: the explicit pre-check above already produces the precise code for
            // the common case. The token hash UNIQUE cannot realistically collide (256 random bits).
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.DEVICES_NAME_TAKEN, null, ex);
        }
    }
}
