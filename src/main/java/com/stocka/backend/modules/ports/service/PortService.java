package com.stocka.backend.modules.ports.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.stocka.backend.modules.common.error.ApiException;
import com.stocka.backend.modules.common.error.ErrorCodes;
import com.stocka.backend.modules.contract.BoardSpec;
import com.stocka.backend.modules.contract.CatalogKind;
import com.stocka.backend.modules.contract.FixtureProfile;
import com.stocka.backend.modules.contract.OutputCatalog;
import com.stocka.backend.modules.contract.OutputKind;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.service.OrganizationService;
import com.stocka.backend.modules.piecetypes.dto.ActionParameterDto;
import com.stocka.backend.modules.piecetypes.entity.AttributeType;
import com.stocka.backend.modules.piecetypes.entity.PieceType;
import com.stocka.backend.modules.piecetypes.repository.PieceTypeRepository;
import com.stocka.backend.modules.piecetypes.service.ActionParametersJsonCodec;
import com.stocka.backend.modules.piecetypes.service.PieceTypeService;
import com.stocka.backend.modules.ports.dto.CreatePortDto;
import com.stocka.backend.modules.ports.dto.UpdatePortDto;
import com.stocka.backend.modules.ports.entity.Port;
import com.stocka.backend.modules.ports.repository.PortRepository;

/**
 * CRUD for {@link Port} definitions: the outputs and inputs of an organization's belén controller,
 * typed by the output catalog ({@link OutputCatalog}).
 *
 * <p>Write rules, in the order {@link #create} applies them:
 * <ol>
 *   <li>{@code kind} is required and must exist in the catalog; it is immutable afterwards.</li>
 *   <li>A port of a non-infrastructure kind needs a piece type of the same organization whose own
 *       kind equals the port's; infrastructure kinds (strips, expanders, sensors) carry no piece type.</li>
 *   <li>Kinds with a pin need a valid, non-reserved GPIO in {@code Port.pin}; the others must not
 *       send one. A relay takes either a pin or {@code expander} + {@code channel} in its config, never
 *       both. A strip's pin must be a WS281x-capable pin and its config channel must match.</li>
 *   <li>Name unique per organization; every pin (including the three motor pins kept in config)
 *       unique across the organization's ports.</li>
 *   <li>Config validated by {@link PortConfigValidator}; a segment must name an existing strip of
 *       the organization and fit inside its length.</li>
 * </ol>
 * Expanders ({@code gpio_expander}) follow the strip/segment pattern: a relay on an expander names an
 * existing expander of the organization, each channel takes one relay, each I2C address one expander
 * per bus, renaming an expander cascades to its relays and deleting one with relays is refused. While
 * an expander exists, GPIO 2 and 3 (I2C) cannot be used by any port. Moving heads ({@code moving_head})
 * must fit their profile's channel count inside the 512-channel universe without overlapping each other.
 * Renaming a strip cascades to the segments that reference it; shortening a strip below one of
 * its segments, or deleting a strip with segments, is refused. The legacy typed-parameter list is
 * kept untouched for compatibility.
 */
@Service
public class PortService {
    private static final int MAX_NAME_LENGTH = 160;
    private static final int MAX_DISPLAY_NAME_LENGTH = 160;
    private static final String SOFT_DELETE_SUFFIX = "::deleted::";
    private static final String STRIP_KEY = "strip";
    private static final String FIRST_KEY = "first";
    private static final String LAST_KEY = "last";
    private static final String LENGTH_KEY = "length";
    private static final String CHANNEL_KEY = "channel";
    private static final String EXPANDER_KEY = PortConfigValidator.EXPANDER_KEY;
    private static final String ADDRESS_KEY = "address";
    private static final String BUS_KEY = "bus";
    private static final String PROFILE_KEY = "profile";
    private static final int DMX_UNIVERSE = 512;
    private static final List<Integer> I2C_PINS = List.of(2, 3);

    private final OrganizationService organizationService;
    private final PortRepository portRepository;
    private final ActionParametersJsonCodec parametersCodec;
    private final PieceTypeService pieceTypeService;
    private final PieceTypeRepository pieceTypeRepository;
    private final OutputCatalog catalog;
    private final PortConfigValidator configValidator;
    private final PortConfigJsonCodec configCodec;

    public PortService(
            OrganizationService organizationService,
            PortRepository portRepository,
            ActionParametersJsonCodec parametersCodec,
            PieceTypeService pieceTypeService,
            PieceTypeRepository pieceTypeRepository,
            OutputCatalog catalog,
            PortConfigValidator configValidator,
            PortConfigJsonCodec configCodec
    ) {
        this.organizationService = organizationService;
        this.portRepository = portRepository;
        this.parametersCodec = parametersCodec;
        this.pieceTypeService = pieceTypeService;
        this.pieceTypeRepository = pieceTypeRepository;
        this.catalog = catalog;
        this.configValidator = configValidator;
        this.configCodec = configCodec;
    }

    /**
     * Lists the ports of an organization ordered by position.
     *
     * @param orgId organization id
     * @return ordered active ports
     */
    public List<Port> listAll(Integer orgId) {
        Organization org = organizationService.findById(orgId);
        return portRepository.findByOrganizationOrderByPositionAscIdAsc(org);
    }

    /**
     * Deserializes the legacy parameter blob of a port.
     *
     * @param port port whose parameters must be read
     * @return the deserialized parameter list (never {@code null})
     */
    public List<ActionParameterDto> parametersOf(Port port) {
        return parametersCodec.deserialize(port.getParametersJson());
    }

    /**
     * Deserializes the kind-specific config of a port.
     *
     * @param port port whose config must be read
     * @return the config map (never {@code null}; empty for kinds without config)
     */
    public Map<String, Object> configOf(Port port) {
        return configCodec.deserialize(port.getConfigJson());
    }

    /**
     * Resolves a port that must belong to the given organization.
     *
     * @param orgId  organization id
     * @param portId port id
     * @return the matching port
     * @throws ApiException 404 ({@link ErrorCodes#PORTS_NOT_FOUND}) when missing or in another org
     */
    public Port findInOrg(Integer orgId, Integer portId) {
        Organization org = organizationService.findById(orgId);
        Port port = portRepository.findById(portId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.PORTS_NOT_FOUND));
        if (!port.getOrganization().getId().equals(org.getId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.PORTS_NOT_FOUND);
        }
        return port;
    }

    /**
     * Segments of the organization that reference the strip named {@code stripName}.
     *
     * @param org       owning organization
     * @param stripName name of a {@code led_strip} port
     * @return the dependent segment ports, in position order
     */
    public List<Port> segmentsOfStrip(Organization org, String stripName) {
        List<Port> out = new ArrayList<>();
        for (Port p : portRepository.findByOrganizationOrderByPositionAscIdAsc(org)) {
            if (p.getKind() == OutputKind.LED_SEGMENT && stripName.equals(configOf(p).get(STRIP_KEY))) {
                out.add(p);
            }
        }
        return out;
    }

    /**
     * Creates a new port in the organization.
     *
     * @param orgId organization id
     * @param dto   create payload
     * @return the persisted port
     */
    @Transactional
    public Port create(Integer orgId, CreatePortDto dto) {
        Organization org = organizationService.findById(orgId);
        String name = sanitizeName(dto.getName());
        OutputKind kind = parseRequiredKind(dto.getKind());
        CatalogKind spec = catalog.kind(kind);
        PieceType pieceType = resolvePieceTypeForKind(orgId, dto.getPieceTypeId(), kind, spec);
        ensureUniqueName(org, name, null);
        Map<String, Object> config = configValidator.normalize(kind, dto.getConfig());
        Integer pin = validatePinForKind(spec, dto.getPin(), config);
        validateStripPin(kind, pin, config);
        ensurePinsFree(org, ownPins(kind, pin, config), null);
        validateSegment(org, kind, config);
        validateExpanderRules(org, kind, pin, config, null);
        validateMovingHead(org, kind, config, null);
        validateMotorLimits(org, kind, config);

        int defaultPosition = portRepository.findByOrganizationOrderByPositionAscIdAsc(org).size();
        List<ActionParameterDto> parameters = normalizeParameters(dto.getParameters());

        Port port = new Port()
                .setOrganization(org)
                .setName(name)
                .setKind(kind)
                .setPieceTypeId(pieceType == null ? null : pieceType.getId())
                .setPin(pin)
                .setConfigJson(configCodec.serialize(config))
                .setPosition(defaultPosition)
                .setParametersJson(parametersCodec.serialize(parameters));
        return saveAndFlush(port);
    }

    /**
     * Applies a partial update to a port. {@code null} fields are left unchanged. The kind cannot
     * change; a new {@code config} replaces the old one and is re-validated together with the pin
     * rules; renaming a strip cascades to its segments.
     *
     * @param orgId  organization id
     * @param portId port id
     * @param dto    partial update payload
     * @return the persisted port
     */
    @Transactional
    public Port update(Integer orgId, Integer portId, UpdatePortDto dto) {
        Port port = findInOrg(orgId, portId);
        Organization org = port.getOrganization();
        OutputKind kind = port.getKind();
        CatalogKind spec = catalog.kind(kind);

        // Validate everything first, mutate the entity only once every rule has passed.
        if (dto.getKind() != null && parseRequiredKind(dto.getKind()) != kind) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_KIND_IMMUTABLE);
        }
        String newName = port.getName();
        if (dto.getName() != null) {
            newName = sanitizeName(dto.getName());
            if (!newName.equals(port.getName())) {
                ensureUniqueName(org, newName, port.getId());
            }
        }
        Integer newPieceTypeId = port.getPieceTypeId();
        if (dto.getPieceTypeId() != null) {
            PieceType pieceType = resolvePieceTypeForKind(orgId, dto.getPieceTypeId(), kind, spec);
            newPieceTypeId = pieceType == null ? null : pieceType.getId();
        }
        Map<String, Object> config = dto.getConfig() != null
                ? configValidator.normalize(kind, dto.getConfig())
                : configOf(port);
        Integer newPin = port.getPin();
        if (dto.getPin() != null || dto.getConfig() != null) {
            // A relay moving to an expander drops its pin; one moving back to a GPIO needs the new pin in the same call.
            Integer requested = dto.getPin() != null ? dto.getPin() : (config.containsKey(EXPANDER_KEY) ? null : port.getPin());
            newPin = validatePinForKind(spec, requested, config);
            validateStripPin(kind, newPin, config);
            ensurePinsFree(org, ownPins(kind, newPin, config), port.getId());
        }
        if (dto.getConfig() != null) {
            validateSegment(org, kind, config);
            if (kind == OutputKind.LED_STRIP) {
                ensureStripKeepsSegments(org, port.getName(), ((Number) config.get(LENGTH_KEY)).intValue());
            }
            validateExpanderRules(org, kind, newPin, config, port.getId());
            validateMovingHead(org, kind, config, port.getId());
            validateMotorLimits(org, kind, config);
        } else if (dto.getPin() != null) {
            ensureNotI2cPinWithExpanders(org, kind, newPin, port.getId());
        }
        List<ActionParameterDto> parameters = dto.getParameters() != null ? normalizeParameters(dto.getParameters()) : null;

        if (!newName.equals(port.getName())) {
            if (kind == OutputKind.LED_STRIP) {
                cascadeStripRename(org, port.getName(), newName);
            }
            if (kind == OutputKind.GPIO_EXPANDER) {
                cascadeExpanderRename(org, port.getName(), newName);
            }
            if (kind == OutputKind.SENSOR) {
                cascadeSensorRename(org, port.getName(), newName);
            }
            port.setName(newName);
        }
        port.setPieceTypeId(newPieceTypeId);
        port.setPin(newPin);
        if (dto.getConfig() != null) {
            port.setConfigJson(configCodec.serialize(config));
        }
        if (parameters != null) {
            port.setParametersJson(parametersCodec.serialize(parameters));
        }
        return saveAndFlush(port);
    }

    /**
     * Soft-deletes a port, freeing both UNIQUE slots: the name is mangled with a "::deleted::ID"
     * suffix and the pin is nulled. A strip that still has segments cannot be deleted.
     *
     * @param orgId  organization id
     * @param portId port id
     * @throws ApiException 409 ({@link ErrorCodes#PORTS_STRIP_IN_USE}) when segments depend on it
     */
    @Transactional
    public void softDelete(Integer orgId, Integer portId) {
        Port port = findInOrg(orgId, portId);
        if (port.getKind() == OutputKind.LED_STRIP) {
            List<Port> segments = segmentsOfStrip(port.getOrganization(), port.getName());
            if (!segments.isEmpty()) {
                throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.PORTS_STRIP_IN_USE,
                        params("segments", String.join(", ", segments.stream().map(Port::getName).toList())));
            }
        }
        if (port.getKind() == OutputKind.GPIO_EXPANDER) {
            List<Port> users = portsOfExpander(port.getOrganization(), port.getName());
            if (!users.isEmpty()) {
                throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.PORTS_EXPANDER_IN_USE,
                        params("relays", String.join(", ", users.stream().map(Port::getName).toList())));
            }
        }
        if (port.getKind() == OutputKind.SENSOR) {
            List<Port> motors = motorsUsingSensor(port.getOrganization(), port.getName());
            if (!motors.isEmpty()) {
                throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.PORTS_SENSOR_IN_USE,
                        params("motors", String.join(", ", motors.stream().map(Port::getName).toList())));
            }
        }
        port.setName(buildSoftDeletedName(port.getName(), port.getId(), MAX_NAME_LENGTH));
        port.setPin(null);
        port.setDeletedAt(LocalDateTime.now());
        portRepository.save(port);
    }

    /**
     * Resolves the display name of a port's related piece type for responses.
     *
     * @param pieceTypeId referenced piece-type id (may be {@code null})
     * @return the piece-type name, or {@code null} when the id is null or the type was soft-deleted
     */
    public String pieceTypeNameOf(Integer pieceTypeId) {
        if (pieceTypeId == null) {
            return null;
        }
        return pieceTypeRepository.findById(pieceTypeId).map(PieceType::getName).orElse(null);
    }

    // ------------------------------------------------------------------ kind / piece type / pin

    private static OutputKind parseRequiredKind(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_KIND_REQUIRED);
        }
        return OutputKind.parse(raw)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_KIND_INVALID,
                        params("kind", raw.trim())));
    }

    /**
     * Resolves the piece type a port of {@code kind} must reference, or {@code null} for
     * infrastructure kinds.
     *
     * @throws ApiException 400 ({@link ErrorCodes#PORTS_PIECE_TYPE_REQUIRED}) when a non-infrastructure
     *                      kind comes without a type, 404 when the type does not exist in the org, or 400
     *                      ({@link ErrorCodes#PORTS_PIECE_TYPE_KIND_MISMATCH}) when the type has no kind
     *                      or another kind
     */
    private PieceType resolvePieceTypeForKind(Integer orgId, Integer pieceTypeId, OutputKind kind, CatalogKind spec) {
        if (pieceTypeId == null) {
            if (spec.infrastructure()) {
                return null;
            }
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_PIECE_TYPE_REQUIRED);
        }
        PieceType type = pieceTypeService.findInOrg(orgId, pieceTypeId);
        if (type.getKind() != kind) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("type", type.getName());
            p.put("typeKind", type.getKind() == null ? "-" : type.getKind().catalogId());
            p.put("portKind", kind.catalogId());
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_PIECE_TYPE_KIND_MISMATCH, p);
        }
        return type;
    }

    private Integer validatePinForKind(CatalogKind spec, Integer pin, Map<String, Object> config) {
        switch (spec.pin()) {
            case NONE -> {
                if (pin != null) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_PIN_NOT_ALLOWED,
                            params("kind", spec.kind().catalogId()));
                }
                return null;
            }
            case OPTIONAL -> {
                boolean onExpander = config != null && config.containsKey(EXPANDER_KEY);
                if (onExpander && pin != null) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_PIN_OR_EXPANDER,
                            params("reason", "both"));
                }
                if (onExpander) {
                    return null;
                }
                if (pin == null) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_PIN_OR_EXPANDER,
                            params("reason", "none"));
                }
                validateGpio(pin);
                return pin;
            }
            default -> {
                if (pin == null) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_PIN_INVALID);
                }
                validateGpio(pin);
                return pin;
            }
        }
    }

    private void validateGpio(int pin) {
        BoardSpec board = board();
        if (!board.isValidGpio(pin)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_PIN_INVALID);
        }
        if (board.isReserved(pin)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_PIN_RESERVED, params("pin", pin));
        }
    }

    private void validateStripPin(OutputKind kind, Integer pin, Map<String, Object> config) {
        if (kind != OutputKind.LED_STRIP || pin == null) {
            return;
        }
        Integer channel = board().ws281xChannel().get(pin);
        if (channel == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_STRIP_PIN_UNSUPPORTED, params("pin", pin));
        }
        Object declared = config.get(CHANNEL_KEY);
        if (declared instanceof Number n && n.intValue() != channel) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("pin", pin);
            p.put("channel", channel);
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_STRIP_CHANNEL_MISMATCH, p);
        }
    }

    /** Pins a port occupies: its own pin plus any pins declared in its config (motor). */
    private List<Integer> ownPins(OutputKind kind, Integer pin, Map<String, Object> config) {
        List<Integer> pins = new ArrayList<>();
        if (pin != null) {
            pins.add(pin);
        }
        for (Integer p : configValidator.configPins(kind, config)) {
            validateGpio(p);
            pins.add(p);
        }
        return pins;
    }

    private void ensurePinsFree(Organization org, List<Integer> pins, Integer excludeId) {
        if (pins.isEmpty()) {
            return;
        }
        Set<Integer> used = pinsInUse(org, excludeId);
        for (Integer pin : pins) {
            if (used.contains(pin)) {
                throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.PORTS_PIN_CONFLICT);
            }
        }
    }

    private Set<Integer> pinsInUse(Organization org, Integer excludeId) {
        Set<Integer> used = new HashSet<>();
        for (Port p : portRepository.findByOrganizationOrderByPositionAscIdAsc(org)) {
            if (excludeId != null && excludeId.equals(p.getId())) {
                continue;
            }
            if (p.getPin() != null) {
                used.add(p.getPin());
            }
            used.addAll(configValidator.configPins(p.getKind(), configOf(p)));
        }
        return used;
    }

    // ------------------------------------------------------------------ strips and segments

    private void validateSegment(Organization org, OutputKind kind, Map<String, Object> config) {
        if (kind != OutputKind.LED_SEGMENT) {
            return;
        }
        String stripName = String.valueOf(config.get(STRIP_KEY));
        Port strip = portRepository.findByOrganizationAndName(org, stripName)
                .filter(p -> p.getKind() == OutputKind.LED_STRIP)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_SEGMENT_STRIP_NOT_FOUND,
                        params(STRIP_KEY, stripName)));
        int length = ((Number) configOf(strip).get(LENGTH_KEY)).intValue();
        int first = ((Number) config.get(FIRST_KEY)).intValue();
        int last = ((Number) config.get(LAST_KEY)).intValue();
        if (Math.min(first, last) < 0 || Math.max(first, last) > length - 1) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put(FIRST_KEY, first);
            p.put(LAST_KEY, last);
            p.put("max", length - 1);
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_SEGMENT_OUT_OF_RANGE, p);
        }
    }

    private void ensureStripKeepsSegments(Organization org, String stripName, int newLength) {
        for (Port segment : segmentsOfStrip(org, stripName)) {
            Map<String, Object> cfg = configOf(segment);
            int first = ((Number) cfg.get(FIRST_KEY)).intValue();
            int last = ((Number) cfg.get(LAST_KEY)).intValue();
            if (Math.max(first, last) > newLength - 1) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("segment", segment.getName());
                p.put(FIRST_KEY, first);
                p.put(LAST_KEY, last);
                throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_STRIP_SHRINK_BREAKS_SEGMENT, p);
            }
        }
    }

    // ------------------------------------------------------------------ expanders

    /**
     * Ports of the organization that hang from the expander named {@code expanderName}: relays,
     * motors and sensors on its channels.
     *
     * @param org          owning organization
     * @param expanderName name of a {@code gpio_expander} port
     * @return the dependent ports, in position order
     */
    public List<Port> portsOfExpander(Organization org, String expanderName) {
        List<Port> out = new ArrayList<>();
        for (Port p : portRepository.findByOrganizationOrderByPositionAscIdAsc(org)) {
            if (p.getKind() != OutputKind.GPIO_EXPANDER && expanderName.equals(configOf(p).get(EXPANDER_KEY))) {
                out.add(p);
            }
        }
        return out;
    }

    /**
     * Motors of the organization that use the sensor named {@code sensorName} as a limit switch.
     *
     * @param org        owning organization
     * @param sensorName name of a {@code sensor} port
     * @return the dependent motor ports, in position order
     */
    public List<Port> motorsUsingSensor(Organization org, String sensorName) {
        List<Port> out = new ArrayList<>();
        for (Port p : portRepository.findByOrganizationOrderByPositionAscIdAsc(org)) {
            if (p.getKind() != OutputKind.MOTOR) {
                continue;
            }
            Map<String, Object> cfg = configOf(p);
            if (PortConfigValidator.MOTOR_LIMIT_KEYS.stream().anyMatch(k -> sensorName.equals(cfg.get(k)))) {
                out.add(p);
            }
        }
        return out;
    }

    private void validateMotorLimits(Organization org, OutputKind kind, Map<String, Object> config) {
        if (kind != OutputKind.MOTOR) {
            return;
        }
        for (String key : PortConfigValidator.MOTOR_LIMIT_KEYS) {
            Object name = config.get(key);
            if (name == null) {
                continue;
            }
            boolean exists = portRepository.findByOrganizationAndName(org, String.valueOf(name))
                    .filter(p -> p.getKind() == OutputKind.SENSOR)
                    .isPresent();
            if (!exists) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("key", key);
                p.put("sensor", name);
                throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_SENSOR_NOT_FOUND, p);
            }
        }
    }

    private void cascadeSensorRename(Organization org, String oldName, String newName) {
        for (Port motor : motorsUsingSensor(org, oldName)) {
            Map<String, Object> cfg = configOf(motor);
            for (String key : PortConfigValidator.MOTOR_LIMIT_KEYS) {
                if (oldName.equals(cfg.get(key))) {
                    cfg.put(key, newName);
                }
            }
            motor.setConfigJson(configCodec.serialize(cfg));
            portRepository.save(motor);
        }
    }

    private void validateExpanderRules(Organization org, OutputKind kind, Integer pin, Map<String, Object> config, Integer excludeId) {
        List<Port> others = portRepository.findByOrganizationOrderByPositionAscIdAsc(org).stream()
                .filter(p -> excludeId == null || !excludeId.equals(p.getId())).toList();
        boolean anyExpander = kind == OutputKind.GPIO_EXPANDER || others.stream().anyMatch(p -> p.getKind() == OutputKind.GPIO_EXPANDER);
        if (kind == OutputKind.GPIO_EXPANDER) {
            int bus = ((Number) config.getOrDefault(BUS_KEY, 1)).intValue();
            int address = ((Number) config.get(ADDRESS_KEY)).intValue();
            for (Port other : others) {
                if (other.getKind() != OutputKind.GPIO_EXPANDER) {
                    continue;
                }
                Map<String, Object> cfg = configOf(other);
                int otherBus = ((Number) cfg.getOrDefault(BUS_KEY, 1)).intValue();
                if (otherBus == bus && cfg.get(ADDRESS_KEY) instanceof Number n && n.intValue() == address) {
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("address", address);
                    p.put("bus", bus);
                    p.put("port", other.getName());
                    throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.PORTS_EXPANDER_ADDRESS_CONFLICT, p);
                }
            }
            // The I2C bus takes GPIO 2 and 3 from now on: no existing port may sit there.
            for (Port other : others) {
                for (Integer used : usedPinsOf(other)) {
                    if (I2C_PINS.contains(used)) {
                        Map<String, Object> p = new LinkedHashMap<>();
                        p.put("pin", used);
                        p.put("port", other.getName());
                        throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.PORTS_I2C_PIN_RESERVED, p);
                    }
                }
            }
        }
        if (config.containsKey(EXPANDER_KEY) && kind != OutputKind.GPIO_EXPANDER) {
            String expanderName = String.valueOf(config.get(EXPANDER_KEY));
            Port expander = others.stream()
                    .filter(p -> p.getKind() == OutputKind.GPIO_EXPANDER && p.getName().equals(expanderName))
                    .findFirst()
                    .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_EXPANDER_NOT_FOUND,
                            params(EXPANDER_KEY, expanderName)));
            // Relays, motors and sensors share the expander's 16 channels.
            for (Integer channel : configValidator.expanderChannels(kind, config)) {
                for (Port other : others) {
                    Map<String, Object> cfg = configOf(other);
                    if (expander.getName().equals(cfg.get(EXPANDER_KEY))
                            && configValidator.expanderChannels(other.getKind(), cfg).contains(channel)) {
                        Map<String, Object> p = new LinkedHashMap<>();
                        p.put(EXPANDER_KEY, expander.getName());
                        p.put(CHANNEL_KEY, channel);
                        p.put("port", other.getName());
                        throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.PORTS_EXPANDER_CHANNEL_CONFLICT, p);
                    }
                }
            }
        }
        if (anyExpander) {
            List<Integer> own = new ArrayList<>();
            if (pin != null) {
                own.add(pin);
            }
            own.addAll(configValidator.configPins(kind, config));
            for (Integer p : own) {
                if (I2C_PINS.contains(p)) {
                    Map<String, Object> params = new LinkedHashMap<>();
                    params.put("pin", p);
                    throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_I2C_PIN_RESERVED, params);
                }
            }
        }
    }

    private void ensureNotI2cPinWithExpanders(Organization org, OutputKind kind, Integer pin, Integer excludeId) {
        if (pin == null || !I2C_PINS.contains(pin)) {
            return;
        }
        boolean anyExpander = portRepository.findByOrganizationOrderByPositionAscIdAsc(org).stream()
                .anyMatch(p -> p.getKind() == OutputKind.GPIO_EXPANDER && (excludeId == null || !excludeId.equals(p.getId())));
        if (anyExpander) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_I2C_PIN_RESERVED, params("pin", pin));
        }
    }

    private List<Integer> usedPinsOf(Port port) {
        List<Integer> pins = new ArrayList<>();
        if (port.getPin() != null) {
            pins.add(port.getPin());
        }
        pins.addAll(configValidator.configPins(port.getKind(), configOf(port)));
        return pins;
    }

    private void cascadeExpanderRename(Organization org, String oldName, String newName) {
        for (Port relay : portsOfExpander(org, oldName)) {
            Map<String, Object> cfg = configOf(relay);
            cfg.put(EXPANDER_KEY, newName);
            relay.setConfigJson(configCodec.serialize(cfg));
            portRepository.save(relay);
        }
    }

    // ------------------------------------------------------------------ moving heads (DMX)

    private void validateMovingHead(Organization org, OutputKind kind, Map<String, Object> config, Integer excludeId) {
        if (kind != OutputKind.MOVING_HEAD) {
            return;
        }
        FixtureProfile profile = catalog.movingHeadProfile(String.valueOf(config.get(PROFILE_KEY)))
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_CONFIG_TYPE_MISMATCH,
                        params(PROFILE_KEY, config.get(PROFILE_KEY))));
        int address = ((Number) config.get(ADDRESS_KEY)).intValue();
        int last = profile.lastChannel(address);
        if (last > DMX_UNIVERSE) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("address", address);
            p.put("channels", profile.channels());
            p.put("last", last);
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_DMX_ADDRESS_RANGE, p);
        }
        for (Port other : portRepository.findByOrganizationOrderByPositionAscIdAsc(org)) {
            if (other.getKind() != OutputKind.MOVING_HEAD || (excludeId != null && excludeId.equals(other.getId()))) {
                continue;
            }
            Map<String, Object> cfg = configOf(other);
            Optional<FixtureProfile> otherProfile = catalog.movingHeadProfile(String.valueOf(cfg.get(PROFILE_KEY)));
            if (otherProfile.isEmpty() || !(cfg.get(ADDRESS_KEY) instanceof Number n)) {
                continue;
            }
            int otherFirst = n.intValue();
            int otherLast = otherProfile.get().lastChannel(otherFirst);
            if (address <= otherLast && otherFirst <= last) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("port", other.getName());
                p.put("first", otherFirst);
                p.put("last", otherLast);
                throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.PORTS_DMX_ADDRESS_CONFLICT, p);
            }
        }
    }

    private void cascadeStripRename(Organization org, String oldName, String newName) {
        for (Port segment : segmentsOfStrip(org, oldName)) {
            Map<String, Object> cfg = configOf(segment);
            cfg.put(STRIP_KEY, newName);
            segment.setConfigJson(configCodec.serialize(cfg));
            portRepository.save(segment);
        }
    }

    private BoardSpec board() {
        return catalog.board(OutputCatalog.DEFAULT_BOARD)
                .orElseThrow(() -> new IllegalStateException("El catálogo no describe la placa " + OutputCatalog.DEFAULT_BOARD));
    }

    // ------------------------------------------------------------------ legacy parameters

    /**
     * Validates and normalizes the legacy inbound parameter list, with the same rules as a piece-type
     * action: valid technical names, non-null types, validated rules, defaulted {@code required},
     * sequential positions and at most one duration parameter.
     *
     * @param raw inbound parameters; may be {@code null}
     * @return a normalized list ready to be serialized (empty when {@code raw} is {@code null})
     * @throws ResponseStatusException with status 400 on any invalid or duplicated parameter
     */
    private List<ActionParameterDto> normalizeParameters(List<ActionParameterDto> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        Set<String> seen = new HashSet<>();
        List<ActionParameterDto> out = new ArrayList<>(raw.size());
        boolean durationSeen = false;
        int idx = 0;
        for (ActionParameterDto param : raw) {
            PieceTypeService.validateActionName(param.getName());
            if (!seen.add(param.getName())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Hay nombres de parámetro duplicados en el puerto: " + param.getName());
            }
            if (param.getType() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "El tipo del parámetro es obligatorio para '" + param.getName() + "'");
            }
            PieceTypeService.validateValidators(param.getType(), param.getValidators());

            boolean required = param.getRequired() == null || param.getRequired();
            boolean dynamic = Boolean.TRUE.equals(param.getDynamic());
            String staticValue = dynamic ? null : trimToNull(param.getStaticValue());
            boolean isDuration = Boolean.TRUE.equals(param.getIsDuration());

            if (isDuration) {
                if (param.getType() != AttributeType.INTEGER && param.getType() != AttributeType.DECIMAL) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "El parámetro de duración '" + param.getName() + "' debe ser numérico");
                }
                if (durationSeen) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Solo un parámetro del puerto puede ser la duración");
                }
                durationSeen = true;
                if (!dynamic && staticValue == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "El parámetro de duración estático '" + param.getName()
                                    + "' necesita un valor fijo");
                }
            } else if (required && !dynamic && staticValue == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "El parámetro estático y obligatorio '" + param.getName()
                                + "' necesita un valor fijo");
            }

            out.add(new ActionParameterDto()
                    .setName(param.getName())
                    .setDisplayName(sanitizeDisplayName(param.getDisplayName(), param.getName()))
                    .setType(param.getType())
                    .setRequired(required)
                    .setPosition(param.getPosition() == null ? idx : param.getPosition())
                    .setValidators(param.getValidators())
                    .setDynamic(dynamic)
                    .setStaticValue(staticValue)
                    .setIsDuration(isDuration));
            idx++;
        }
        return out;
    }

    private static String trimToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void ensureUniqueName(Organization org, String name, Integer excludeId) {
        Optional<Port> existing = portRepository.findByOrganizationAndName(org, name);
        if (existing.isPresent() && (excludeId == null || !existing.get().getId().equals(excludeId))) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.PORTS_NAME_CONFLICT);
        }
    }

    private String sanitizeName(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_NAME_REQUIRED);
        }
        String trimmed = raw.trim();
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El nombre del puerto no puede superar " + MAX_NAME_LENGTH + " caracteres");
        }
        return trimmed;
    }

    private String sanitizeDisplayName(String raw, String fallback) {
        String value = (raw == null || raw.isBlank()) ? fallback : raw.trim();
        if (value.length() > MAX_DISPLAY_NAME_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El nombre visible no puede superar " + MAX_DISPLAY_NAME_LENGTH + " caracteres");
        }
        return value;
    }

    /**
     * Builds the renamed value for a soft-deleted row so its original name is freed for a new active
     * row with the same name. The id-based suffix guarantees the renamed value stays unique across
     * all previously soft-deleted rows.
     */
    private static String buildSoftDeletedName(String currentName, Integer id, int maxLength) {
        String suffix = SOFT_DELETE_SUFFIX + (id == null ? "?" : id);
        int maxBaseLen = Math.max(0, maxLength - suffix.length());
        String base = currentName == null ? "" : currentName;
        if (base.length() > maxBaseLen) {
            base = base.substring(0, maxBaseLen);
        }
        return base + suffix;
    }

    private static Map<String, Object> params(String key, Object value) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put(key, value);
        return p;
    }

    private Port saveAndFlush(Port port) {
        try {
            return portRepository.saveAndFlush(port);
        } catch (DataIntegrityViolationException ex) {
            // Race backstop only: the explicit pre-checks above already produce the precise code for
            // the common case. With two UNIQUE constraints the exception alone cannot tell which one
            // fired, so fall back to inspecting the constraint name ("...org_pin" vs "...org_name").
            String cause = ex.getMostSpecificCause().getMessage();
            String code = (cause != null && cause.toLowerCase().contains("pin"))
                    ? ErrorCodes.PORTS_PIN_CONFLICT
                    : ErrorCodes.PORTS_NAME_CONFLICT;
            throw new ApiException(HttpStatus.CONFLICT, code, null, ex);
        }
    }
}
