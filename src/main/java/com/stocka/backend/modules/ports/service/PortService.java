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
 *       kind equals the port's; infrastructure kinds (strips, button) carry no piece type.</li>
 *   <li>Kinds with a pin need a valid, non-reserved GPIO in {@code Port.pin}; the others must not
 *       send one. A strip's pin must be a WS281x-capable pin and its config channel must match.</li>
 *   <li>Name unique per organization; every pin (including the three motor pins kept in config)
 *       unique across the organization's ports.</li>
 *   <li>Config validated by {@link PortConfigValidator}; a segment must name an existing strip of
 *       the organization and fit inside its length.</li>
 * </ol>
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
        Integer pin = validatePinForKind(spec, dto.getPin());
        ensureUniqueName(org, name, null);
        Map<String, Object> config = configValidator.normalize(kind, dto.getConfig());
        validateStripPin(kind, pin, config);
        ensurePinsFree(org, ownPins(kind, pin, config), null);
        validateSegment(org, kind, config);

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
        Integer newPin = dto.getPin() != null ? validatePinForKind(spec, dto.getPin()) : port.getPin();
        Map<String, Object> config = dto.getConfig() != null
                ? configValidator.normalize(kind, dto.getConfig())
                : configOf(port);
        if (dto.getConfig() != null || dto.getPin() != null) {
            validateStripPin(kind, newPin, config);
            ensurePinsFree(org, ownPins(kind, newPin, config), port.getId());
        }
        if (dto.getConfig() != null) {
            validateSegment(org, kind, config);
            if (kind == OutputKind.LED_STRIP) {
                ensureStripKeepsSegments(org, port.getName(), ((Number) config.get(LENGTH_KEY)).intValue());
            }
        }
        List<ActionParameterDto> parameters = dto.getParameters() != null ? normalizeParameters(dto.getParameters()) : null;

        if (!newName.equals(port.getName())) {
            if (kind == OutputKind.LED_STRIP) {
                cascadeStripRename(org, port.getName(), newName);
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

    private Integer validatePinForKind(CatalogKind spec, Integer pin) {
        if (!spec.pin()) {
            if (pin != null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_PIN_NOT_ALLOWED,
                        params("kind", spec.kind().catalogId()));
            }
            return null;
        }
        if (pin == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_PIN_INVALID);
        }
        validateGpio(pin);
        return pin;
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
            PieceTypeService.validateAttributeName(param.getName());
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
