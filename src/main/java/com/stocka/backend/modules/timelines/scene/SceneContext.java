package com.stocka.backend.modules.timelines.scene;

import java.util.List;
import java.util.Optional;

import com.stocka.backend.modules.piecetypes.dto.ActionParameterDto;

/**
 * What the scene engine needs to know about the organization: its pieces, ports and the actions
 * of piece types. Implemented over JPA for real use and by a stub in tests.
 */
public interface SceneContext {

    /** A piece of the organization. */
    record PieceRef(int id, String name, List<Integer> typeIds, List<String> typeNames, List<String> typeKinds) {
    }

    /** A hardware port of the organization. */
    record PortRef(int id, String name, Integer pieceTypeId, String kind) {
    }

    /** An action of a piece type. */
    record ActionRef(int id, String name, String displayName, int typeId, String typeName, String kind,
                     List<ActionParameterDto> parameters) {
    }

    Optional<PieceRef> pieceById(int id);

    /** @return pieces whose name matches, case-insensitively (exact match preferred by callers) */
    List<PieceRef> piecesByName(String name);

    Optional<PortRef> portById(int id);

    Optional<PortRef> portByName(String name);

    /** @return the actions of every type of {@code piece} */
    List<ActionRef> actionsOfPiece(PieceRef piece);

    Optional<ActionRef> actionById(int id);

    /** @return a fresh id for a layer/item/track/clip, e.g. {@code item-<uuid>} */
    String newId(String prefix);
}
