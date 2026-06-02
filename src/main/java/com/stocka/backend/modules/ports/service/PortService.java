package com.stocka.backend.modules.ports.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.stocka.backend.modules.common.error.ApiException;
import com.stocka.backend.modules.common.error.ErrorCodes;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.service.OrganizationService;
import com.stocka.backend.modules.piecetypes.dto.ActionParameterDto;
import com.stocka.backend.modules.piecetypes.entity.PieceType;
import com.stocka.backend.modules.piecetypes.repository.PieceTypeRepository;
import com.stocka.backend.modules.piecetypes.service.ActionParametersJsonCodec;
import com.stocka.backend.modules.piecetypes.service.PieceTypeService;
import com.stocka.backend.modules.ports.dto.CreatePortDto;
import com.stocka.backend.modules.ports.dto.UpdatePortDto;
import com.stocka.backend.modules.ports.entity.Port;
import com.stocka.backend.modules.ports.repository.PortRepository;

/**
 * CRUD for {@link Port} definitions (per-organization Raspberry Pi GPIO outputs with typed
 * parameters). Ports are a private, organization-gated feature: the authorization gate lives in
 * {@code @orgSecurity.canReadPorts} / {@code canManagePorts}. Definitions only: there is no runtime
 * hardware execution.
 *
 * <p>Parameter names follow the same technical-identifier rules as attributes
 * ({@link PieceTypeService#validateAttributeName(String)}) and parameter validators reuse
 * {@link PieceTypeService#validateValidators}. The ordered parameter list is persisted as a JSON
 * blob through {@link ActionParametersJsonCodec}, exactly like a piece-type action.
 *
 * <p>The port {@code name} is a human label ("Salida tira led 1"), not a technical identifier, so it
 * is only trimmed and length-checked. The {@code pin} is the Raspberry Pi GPIO number and is unique
 * per organization; on soft-delete the name is mangled and the pin nulled to free both UNIQUE slots.
 */
@Service
public class PortService {
    private static final int MAX_NAME_LENGTH = 160;
    private static final int MAX_DISPLAY_NAME_LENGTH = 160;
    private static final String SOFT_DELETE_SUFFIX = "::deleted::";

    private final OrganizationService organizationService;
    private final PortRepository portRepository;
    private final ActionParametersJsonCodec parametersCodec;
    private final PieceTypeService pieceTypeService;
    private final PieceTypeRepository pieceTypeRepository;

    public PortService(
            OrganizationService organizationService,
            PortRepository portRepository,
            ActionParametersJsonCodec parametersCodec,
            PieceTypeService pieceTypeService,
            PieceTypeRepository pieceTypeRepository
    ) {
        this.organizationService = organizationService;
        this.portRepository = portRepository;
        this.parametersCodec = parametersCodec;
        this.pieceTypeService = pieceTypeService;
        this.pieceTypeRepository = pieceTypeRepository;
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
     * Deserializes the parameter blob of a port, so controllers can build responses without
     * depending on the codec bean directly.
     *
     * @param port port whose parameters must be read
     * @return the deserialized parameter list (never {@code null})
     */
    public List<ActionParameterDto> parametersOf(Port port) {
        return parametersCodec.deserialize(port.getParametersJson());
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
        Integer pin = validatePin(dto.getPin());
        PieceType pieceType = resolvePieceType(orgId, dto.getPieceTypeId());
        ensureUniqueName(org, name, null);
        ensureUniquePin(org, pin, null);

        int defaultPosition = portRepository.findByOrganizationOrderByPositionAscIdAsc(org).size();
        List<ActionParameterDto> parameters = normalizeParameters(dto.getParameters());

        Port port = new Port()
                .setOrganization(org)
                .setName(name)
                .setPieceTypeId(pieceType.getId())
                .setPin(pin)
                .setPosition(defaultPosition)
                .setParametersJson(parametersCodec.serialize(parameters));
        return saveAndFlush(port);
    }

    /**
     * Applies a partial update to a port. {@code null} fields are left unchanged; a non-null
     * {@code parameters} list replaces the existing parameter set.
     *
     * @param orgId  organization id
     * @param portId port id
     * @param dto    partial update payload
     * @return the persisted port
     */
    @Transactional
    public Port update(Integer orgId, Integer portId, UpdatePortDto dto) {
        Port port = findInOrg(orgId, portId);

        if (dto.getName() != null) {
            String newName = sanitizeName(dto.getName());
            if (!newName.equals(port.getName())) {
                ensureUniqueName(port.getOrganization(), newName, port.getId());
                port.setName(newName);
            }
        }
        if (dto.getPieceTypeId() != null) {
            PieceType pieceType = resolvePieceType(orgId, dto.getPieceTypeId());
            port.setPieceTypeId(pieceType.getId());
        }
        if (dto.getPin() != null) {
            Integer newPin = validatePin(dto.getPin());
            if (!newPin.equals(port.getPin())) {
                ensureUniquePin(port.getOrganization(), newPin, port.getId());
                port.setPin(newPin);
            }
        }
        if (dto.getParameters() != null) {
            List<ActionParameterDto> parameters = normalizeParameters(dto.getParameters());
            port.setParametersJson(parametersCodec.serialize(parameters));
        }
        return saveAndFlush(port);
    }

    /**
     * Soft-deletes a port, freeing both UNIQUE slots: the name is mangled with a "::deleted::ID"
     * suffix and the pin is nulled (InnoDB treats multiple NULLs as distinct), so a fresh active
     * port with the same name and/or pin can be created later.
     *
     * @param orgId  organization id
     * @param portId port id
     */
    @Transactional
    public void softDelete(Integer orgId, Integer portId) {
        Port port = findInOrg(orgId, portId);
        port.setName(buildSoftDeletedName(port.getName(), port.getId(), MAX_NAME_LENGTH));
        port.setPin(null);
        port.setDeletedAt(LocalDateTime.now());
        portRepository.save(port);
    }

    /**
     * Validates and normalizes the inbound parameter list: each parameter gets a valid technical
     * name, a non-null type, validated type-specific rules, a defaulted {@code required} flag, a
     * sequential position and a display name. Parameter names must be unique within the port.
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

            out.add(new ActionParameterDto()
                    .setName(param.getName())
                    .setDisplayName(sanitizeDisplayName(param.getDisplayName(), param.getName()))
                    .setType(param.getType())
                    .setRequired(param.getRequired() == null || param.getRequired())
                    .setPosition(param.getPosition() == null ? idx : param.getPosition())
                    .setValidators(param.getValidators()));
            idx++;
        }
        return out;
    }

    private void ensureUniqueName(Organization org, String name, Integer excludeId) {
        Optional<Port> existing = portRepository.findByOrganizationAndName(org, name);
        if (existing.isPresent() && (excludeId == null || !existing.get().getId().equals(excludeId))) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.PORTS_NAME_CONFLICT);
        }
    }

    private void ensureUniquePin(Organization org, Integer pin, Integer excludeId) {
        Optional<Port> existing = portRepository.findByOrganizationAndPin(org, pin);
        if (existing.isPresent() && (excludeId == null || !existing.get().getId().equals(excludeId))) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.PORTS_PIN_CONFLICT);
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

    /**
     * Resolves the related piece type, validating it exists and belongs to the same organization.
     *
     * @param orgId       organization id
     * @param pieceTypeId referenced piece-type id; must be non-null
     * @return the resolved piece type
     * @throws ApiException 400 ({@link ErrorCodes#PORTS_PIECE_TYPE_REQUIRED}) when {@code pieceTypeId}
     *                      is null, or 404 ({@code piece_types.not_found}) when it does not exist in
     *                      the organization
     */
    private PieceType resolvePieceType(Integer orgId, Integer pieceTypeId) {
        if (pieceTypeId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_PIECE_TYPE_REQUIRED);
        }
        return pieceTypeService.findInOrg(orgId, pieceTypeId);
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

    private Integer validatePin(Integer pin) {
        if (pin == null || pin < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.PORTS_PIN_INVALID);
        }
        return pin;
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
