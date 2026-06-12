package com.stocka.backend.modules.piecetypes.service;

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

import com.stocka.backend.modules.piecetypes.dto.ActionParameterDto;
import com.stocka.backend.modules.piecetypes.dto.CreatePieceTypeActionDto;
import com.stocka.backend.modules.piecetypes.dto.UpdatePieceTypeActionDto;
import com.stocka.backend.modules.piecetypes.entity.AttributeType;
import com.stocka.backend.modules.piecetypes.entity.PieceType;
import com.stocka.backend.modules.piecetypes.entity.PieceTypeAction;
import com.stocka.backend.modules.piecetypes.repository.PieceTypeActionRepository;

/**
 * CRUD for {@link PieceTypeAction} definitions (functions with typed parameters) of a piece type.
 *
 * <p>Action and parameter names follow the same technical-identifier rules as attributes
 * ({@link PieceTypeService#validateAttributeName(String)}) and parameter validators reuse
 * {@link PieceTypeService#validateValidators}. The ordered parameter list is persisted as a JSON
 * blob through {@link ActionParametersJsonCodec}. Definitions only: there is no runtime execution.
 */
@Service
public class PieceTypeActionService {
    private static final int MAX_NAME_LENGTH = 80;
    private static final int MAX_DISPLAY_NAME_LENGTH = 160;
    private static final int MAX_DESCRIPTION_LENGTH = 255;

    private final PieceTypeService pieceTypeService;
    private final PieceTypeActionRepository actionRepository;
    private final ActionParametersJsonCodec parametersCodec;

    public PieceTypeActionService(
            PieceTypeService pieceTypeService,
            PieceTypeActionRepository actionRepository,
            ActionParametersJsonCodec parametersCodec
    ) {
        this.pieceTypeService = pieceTypeService;
        this.actionRepository = actionRepository;
        this.parametersCodec = parametersCodec;
    }

    /**
     * Lists the actions of {@code type} ordered by position.
     *
     * @param type owning piece type
     * @return ordered actions
     */
    public List<PieceTypeAction> listOf(PieceType type) {
        return actionRepository.findByPieceTypeOrderByPositionAscIdAsc(type);
    }

    /**
     * Resolves the codec used to (de)serialize the parameter blob, so controllers can build
     * responses without depending on the codec bean directly.
     *
     * @param action action whose parameters must be read
     * @return the deserialized parameter list (never {@code null})
     */
    public List<ActionParameterDto> parametersOf(PieceTypeAction action) {
        return parametersCodec.deserialize(action.getParametersJson());
    }

    public PieceTypeAction findInOrg(Integer orgId, Integer typeId, Integer actionId) {
        PieceType type = pieceTypeService.findInOrg(orgId, typeId);
        PieceTypeAction action = actionRepository.findById(actionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Acción no encontrada"));
        if (!action.getPieceType().getId().equals(type.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Acción no encontrada");
        }
        return action;
    }

    @Transactional
    public PieceTypeAction create(Integer orgId, Integer typeId, CreatePieceTypeActionDto dto) {
        PieceType type = pieceTypeService.findInOrg(orgId, typeId);
        PieceTypeService.validateAttributeName(dto.getName());
        ensureUniqueActionName(type, dto.getName(), null);

        int defaultPosition = actionRepository.findByPieceTypeOrderByPositionAscIdAsc(type).size();
        List<ActionParameterDto> parameters = normalizeParameters(dto.getParameters());

        PieceTypeAction action = new PieceTypeAction()
                .setPieceType(type)
                .setName(dto.getName())
                .setDisplayName(sanitizeDisplayName(dto.getDisplayName(), dto.getName()))
                .setDescription(sanitizeDescription(dto.getDescription()))
                .setPosition(dto.getPosition() == null ? defaultPosition : dto.getPosition())
                .setParametersJson(parametersCodec.serialize(parameters));
        return saveAndFlush(action);
    }

    @Transactional
    public PieceTypeAction update(Integer orgId, Integer typeId, Integer actionId, UpdatePieceTypeActionDto dto) {
        PieceTypeAction action = findInOrg(orgId, typeId, actionId);

        if (dto.getName() != null) {
            String trimmed = dto.getName().trim();
            if (!trimmed.equals(action.getName())) {
                PieceTypeService.validateAttributeName(trimmed);
                ensureUniqueActionName(action.getPieceType(), trimmed, action.getId());
                action.setName(trimmed);
            }
        }
        if (dto.getDisplayName() != null) {
            String trimmed = dto.getDisplayName().trim();
            if (trimmed.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El nombre visible no puede estar vacío");
            }
            action.setDisplayName(sanitizeDisplayName(trimmed, action.getName()));
        }
        if (dto.getDescription() != null) {
            action.setDescription(sanitizeDescription(dto.getDescription()));
        }
        if (dto.getPosition() != null) {
            action.setPosition(dto.getPosition());
        }
        if (dto.getParameters() != null) {
            List<ActionParameterDto> parameters = normalizeParameters(dto.getParameters());
            action.setParametersJson(parametersCodec.serialize(parameters));
        }
        return saveAndFlush(action);
    }

    @Transactional
    public void softDelete(Integer orgId, Integer typeId, Integer actionId) {
        PieceTypeAction action = findInOrg(orgId, typeId, actionId);
        // Free the (piece_type_id, name) slot so a fresh action with the same technical name can be
        // added later without colliding with the uk_piece_type_action_type_name UNIQUE, which
        // covers all rows regardless of deleted_at.
        action.setName(PieceTypeService.buildSoftDeletedName(action.getName(), action.getId(), MAX_NAME_LENGTH));
        action.setDeletedAt(LocalDateTime.now());
        actionRepository.save(action);
    }

    /**
     * Validates and normalizes the inbound parameter list: each parameter gets a valid technical
     * name, a non-null type, validated type-specific rules, a defaulted {@code required} flag, a
     * sequential position, a display name and a resolved binding mode. Parameter names must be
     * unique within the action.
     *
     * <p>Binding rules: a parameter is <b>static</b> by default ({@code dynamic} defaults to
     * {@code false}); a static parameter keeps its trimmed {@code staticValue} (blank becomes
     * {@code null}), while a dynamic one always has its {@code staticValue} cleared since the value is
     * supplied per clip in the timeline. A parameter that is both <b>required and static</b> must
     * carry a non-blank {@code staticValue}, otherwise it would have no usable value anywhere.
     *
     * <p>Duration rules: at most one parameter per action may be flagged as the duration
     * ({@code isDuration}); it must be numeric ({@code INTEGER} or {@code DECIMAL}) and, when static,
     * must carry a {@code staticValue} since the clip length cannot be left undefined.
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
                        "Hay nombres de parámetro duplicados en la acción: " + param.getName());
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
                            "Solo un parámetro de la acción puede ser la duración");
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

    private void ensureUniqueActionName(PieceType type, String name, Integer excludeId) {
        if (name == null) return;
        Optional<PieceTypeAction> existing = actionRepository.findByPieceTypeAndName(type, name);
        if (existing.isPresent() && (excludeId == null || !existing.get().getId().equals(excludeId))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ya existe una acción con ese nombre técnico en el tipo");
        }
    }

    private String sanitizeDisplayName(String raw, String fallback) {
        String value = (raw == null || raw.isBlank()) ? fallback : raw.trim();
        if (value.length() > MAX_DISPLAY_NAME_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El nombre visible no puede superar " + MAX_DISPLAY_NAME_LENGTH + " caracteres");
        }
        return value;
    }

    private String sanitizeDescription(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MAX_DESCRIPTION_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La descripción no puede superar " + MAX_DESCRIPTION_LENGTH + " caracteres");
        }
        return trimmed;
    }

    private PieceTypeAction saveAndFlush(PieceTypeAction action) {
        try {
            return actionRepository.saveAndFlush(action);
        } catch (DataIntegrityViolationException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ya existe una acción con ese nombre técnico en el tipo");
        }
    }
}
