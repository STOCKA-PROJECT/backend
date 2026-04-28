package com.stocka.backend.modules.pieces.service;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.stocka.backend.modules.pieces.entity.PieceAttributeValue;
import com.stocka.backend.modules.pieces.entity.PieceStatus;
import com.stocka.backend.modules.piecetypes.entity.PieceTypeAttribute;

/**
 * Stateless helper that decides whether a piece is {@link PieceStatus#ACTIVE} or
 * {@link PieceStatus#PENDING} based on which required attributes have a non-blank value.
 */
@Component
public class PieceStatusCalculator {

    /**
     * @param attributes the attribute schema for the piece's type
     * @param values     all values currently stored for the piece (any order)
     * @return ACTIVE when every required attribute has a value; PENDING otherwise
     */
    public PieceStatus compute(Collection<PieceTypeAttribute> attributes, Collection<PieceAttributeValue> values) {
        Set<Integer> filled = new HashSet<>();
        for (PieceAttributeValue v : values) {
            if (v.getValue() != null && !v.getValue().isBlank()) {
                filled.add(v.getAttribute().getId());
            }
        }
        for (PieceTypeAttribute attr : attributes) {
            if (attr.isRequired() && !filled.contains(attr.getId())) {
                return PieceStatus.PENDING;
            }
        }
        return PieceStatus.ACTIVE;
    }

    /** Convenience overload that takes pre-built map of value-by-attributeId. */
    public PieceStatus compute(Collection<PieceTypeAttribute> attributes, Map<Integer, PieceAttributeValue> valuesByAttrId) {
        return compute(attributes, valuesByAttrId.values());
    }
}
