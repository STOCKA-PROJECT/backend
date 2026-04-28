package com.stocka.backend.modules.piecetypes.service;

import com.stocka.backend.modules.piecetypes.entity.PieceType;
import com.stocka.backend.modules.piecetypes.entity.PieceTypeAttribute;

/**
 * Hook the pieces module implements so the piece-types module can:
 * <ul>
 *   <li>Refuse to delete a type that still has pieces ({@link #countPiecesOfType}).</li>
 *   <li>Trigger a status recalculation across all pieces when an attribute is added, modified or
 *       deleted ({@link #recalcStatusForType} / {@link #removeValuesForAttribute}).</li>
 * </ul>
 *
 * Wired as an {@code Optional} dependency so the piece-types module can be tested in isolation.
 */
public interface PieceTypeUsage {

    long countPiecesOfType(PieceType pieceType);

    /**
     * Recalculates {@code status} (ACTIVE/PENDING) for every piece of {@code pieceType} and
     * persists changes. Records {@code STATUS_CHANGED} entries in piece history when the status
     * actually moves. Used after an attribute's {@code required} flag changes or a new required
     * attribute is added.
     */
    void recalcStatusForType(PieceType pieceType);

    /**
     * Deletes every {@code piece_attribute_values} row referencing {@code attribute} so the
     * attribute itself can be soft-deleted without violating FKs.
     */
    void removeValuesForAttribute(PieceTypeAttribute attribute);
}
