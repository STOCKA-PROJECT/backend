package com.stocka.backend.modules.timelines.scene;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.pieces.entity.Piece;
import com.stocka.backend.modules.pieces.repository.PieceRepository;
import com.stocka.backend.modules.piecetypes.entity.PieceType;
import com.stocka.backend.modules.piecetypes.entity.PieceTypeAction;
import com.stocka.backend.modules.piecetypes.repository.PieceTypeActionRepository;
import com.stocka.backend.modules.piecetypes.service.PieceTypeActionService;
import com.stocka.backend.modules.piecetypes.service.PieceTypeService;
import com.stocka.backend.modules.ports.entity.Port;
import com.stocka.backend.modules.ports.repository.PortRepository;

/**
 * {@link SceneContext} over the organization's JPA data, caching what it loads for the duration of
 * one edit or validation.
 */
public class JpaSceneContext implements SceneContext {

    private final Organization org;
    private final PieceRepository pieces;
    private final PortRepository ports;
    private final PieceTypeActionRepository actions;
    private final PieceTypeService typeService;
    private final PieceTypeActionService actionService;

    private final Map<Integer, Optional<PieceRef>> pieceCache = new HashMap<>();
    private final Map<Integer, Optional<ActionRef>> actionCache = new HashMap<>();
    private final Map<Integer, List<ActionRef>> actionsByType = new HashMap<>();
    private List<Piece> allPieces;
    private List<Port> allPorts;

    public JpaSceneContext(Organization org, PieceRepository pieces, PortRepository ports,
                           PieceTypeActionRepository actions, PieceTypeService typeService,
                           PieceTypeActionService actionService) {
        this.org = org;
        this.pieces = pieces;
        this.ports = ports;
        this.actions = actions;
        this.typeService = typeService;
        this.actionService = actionService;
    }

    @Override
    public Optional<PieceRef> pieceById(int id) {
        return pieceCache.computeIfAbsent(id, k -> pieces.findByIdAndOrganization(k, org).map(JpaSceneContext::ref));
    }

    @Override
    public List<PieceRef> piecesByName(String name) {
        if (allPieces == null) {
            allPieces = pieces.findByOrganization(org);
        }
        String needle = name.toLowerCase(Locale.ROOT);
        List<PieceRef> out = new ArrayList<>();
        for (Piece piece : allPieces) {
            String n = piece.getName() == null ? "" : piece.getName().toLowerCase(Locale.ROOT);
            if (n.equals(needle) || n.contains(needle)) {
                PieceRef ref = ref(piece);
                pieceCache.put(piece.getId(), Optional.of(ref));
                out.add(ref);
            }
        }
        return out;
    }

    @Override
    public Optional<PortRef> portById(int id) {
        return loadPorts().stream().filter(p -> p.getId().equals(id)).findFirst().map(JpaSceneContext::ref);
    }

    @Override
    public Optional<PortRef> portByName(String name) {
        return loadPorts().stream().filter(p -> p.getName() != null && p.getName().equalsIgnoreCase(name)).findFirst()
                .map(JpaSceneContext::ref);
    }

    @Override
    public List<ActionRef> actionsOfPiece(PieceRef piece) {
        List<ActionRef> out = new ArrayList<>();
        for (Integer typeId : piece.typeIds()) {
            out.addAll(actionsByType.computeIfAbsent(typeId, this::loadActions));
        }
        return out;
    }

    @Override
    public Optional<ActionRef> actionById(int id) {
        return actionCache.computeIfAbsent(id, k -> actions.findById(k)
                .filter(a -> a.getPieceType() != null && a.getPieceType().getOrganization() != null
                        && org.getId().equals(a.getPieceType().getOrganization().getId()))
                .map(this::ref));
    }

    @Override
    public String newId(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private List<Port> loadPorts() {
        if (allPorts == null) {
            allPorts = ports.findByOrganizationOrderByPositionAscIdAsc(org);
        }
        return allPorts;
    }

    private List<ActionRef> loadActions(Integer typeId) {
        List<ActionRef> out = new ArrayList<>();
        PieceType type;
        try {
            type = typeService.findInOrg(org.getId(), typeId);
        } catch (RuntimeException e) {
            return out;
        }
        for (PieceTypeAction action : actionService.listOf(type)) {
            ActionRef ref = ref(action);
            actionCache.put(action.getId(), Optional.of(ref));
            out.add(ref);
        }
        return out;
    }

    private ActionRef ref(PieceTypeAction action) {
        PieceType type = action.getPieceType();
        return new ActionRef(action.getId(), action.getName(), action.getDisplayName(), type.getId(), type.getName(),
                type.getKind() == null ? null : type.getKind().catalogId(), actionService.parametersOf(action));
    }

    private static PieceRef ref(Piece piece) {
        List<Integer> typeIds = new ArrayList<>();
        List<String> typeNames = new ArrayList<>();
        List<String> kinds = new ArrayList<>();
        for (PieceType type : piece.getPieceTypes()) {
            typeIds.add(type.getId());
            typeNames.add(type.getName());
            kinds.add(type.getKind() == null ? null : type.getKind().catalogId());
        }
        return new PieceRef(piece.getId(), piece.getName(), typeIds, typeNames, kinds);
    }

    private static PortRef ref(Port port) {
        return new PortRef(port.getId(), port.getName(), port.getPieceTypeId(),
                port.getKind() == null ? null : port.getKind().catalogId());
    }
}
