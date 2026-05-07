package com.stocka.backend.modules.pieces.service;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.stocka.backend.modules.organizations.entity.OrganizationPieceAttribute;
import com.stocka.backend.modules.pieces.entity.PieceAttributeValue;
import com.stocka.backend.modules.pieces.entity.PieceOrganizationAttributeValue;
import com.stocka.backend.modules.pieces.entity.PieceStatus;
import com.stocka.backend.modules.piecetypes.entity.PieceTypeAttribute;

/**
 * Stateless helper that decides whether a piece is {@link PieceStatus#ACTIVE} or
 * {@link PieceStatus#PENDING} based on which required attributes (type-level and
 * organization-level) have a non-blank value.
 *
 * <p>The two attribute spaces share the same {@code id} sequence, so the calculator builds
 * prefixed string keys ({@code "type:"+id}, {@code "org:"+id}) before comparing what is required
 * against what is filled. A piece is ACTIVE only when every required attribute — across both
 * spaces — has a filled value.
 */
@Component
public class PieceStatusCalculator {

    private static final String TYPE_PREFIX = "type:";
    private static final String ORG_PREFIX = "org:";

    /**
     * @param typeAttributes type-level attribute schema applicable to the piece
     * @param typeValues     all type-scope values currently stored for the piece
     * @param orgAttributes  organization-level attribute schema applicable to the piece's org
     * @param orgValues      all org-scope values currently stored for the piece
     * @return ACTIVE when every required attribute has a value; PENDING otherwise
     */
    public PieceStatus compute(
            Collection<PieceTypeAttribute> typeAttributes,
            Collection<PieceAttributeValue> typeValues,
            Collection<OrganizationPieceAttribute> orgAttributes,
            Collection<PieceOrganizationAttributeValue> orgValues
    ) {
        Set<String> filled = new HashSet<>();
        for (PieceAttributeValue v : typeValues) {
            if (v.getValue() != null && !v.getValue().isBlank()) {
                filled.add(TYPE_PREFIX + v.getAttribute().getId());
            }
        }
        for (PieceOrganizationAttributeValue v : orgValues) {
            if (v.getValue() != null && !v.getValue().isBlank()) {
                filled.add(ORG_PREFIX + v.getAttribute().getId());
            }
        }
        for (PieceTypeAttribute attr : typeAttributes) {
            if (attr.isRequired() && !filled.contains(TYPE_PREFIX + attr.getId())) {
                return PieceStatus.PENDING;
            }
        }
        for (OrganizationPieceAttribute attr : orgAttributes) {
            if (attr.isRequired() && !filled.contains(ORG_PREFIX + attr.getId())) {
                return PieceStatus.PENDING;
            }
        }
        return PieceStatus.ACTIVE;
    }

    /**
     * Convenience overload for callers that already grouped values by attribute id.
     *
     * @param typeAttributes      type-level attribute schema applicable to the piece
     * @param typeValuesByAttrId  type-scope values keyed by attribute id
     * @param orgAttributes       organization-level attribute schema applicable to the piece's org
     * @param orgValuesByAttrId   org-scope values keyed by attribute id
     * @return ACTIVE when every required attribute has a value; PENDING otherwise
     */
    public PieceStatus compute(
            Collection<PieceTypeAttribute> typeAttributes,
            Map<Integer, PieceAttributeValue> typeValuesByAttrId,
            Collection<OrganizationPieceAttribute> orgAttributes,
            Map<Integer, PieceOrganizationAttributeValue> orgValuesByAttrId
    ) {
        return compute(typeAttributes, typeValuesByAttrId.values(),
                orgAttributes, orgValuesByAttrId.values());
    }

    /**
     * Type-only overload preserved for callers that do not yet wire organization-level attributes.
     * Equivalent to invoking the four-argument variant with empty org collections.
     */
    public PieceStatus compute(Collection<PieceTypeAttribute> typeAttributes,
                               Collection<PieceAttributeValue> typeValues) {
        return compute(typeAttributes, typeValues, java.util.List.of(), java.util.List.of());
    }

    /**
     * Type-only convenience overload that takes a values map. Equivalent to invoking the
     * four-argument map-based variant with an empty org map.
     */
    public PieceStatus compute(Collection<PieceTypeAttribute> typeAttributes,
                               Map<Integer, PieceAttributeValue> typeValuesByAttrId) {
        return compute(typeAttributes, typeValuesByAttrId, java.util.List.of(), java.util.Map.of());
    }
}
