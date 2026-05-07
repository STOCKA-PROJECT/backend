package com.stocka.backend.modules.pieces.dto;

/**
 * One attribute value sent by the client when creating or updating a piece.
 *
 * @param attributeId id of the targeted attribute (either {@code piece_type_attributes.id} or
 *                    {@code organization_piece_attributes.id} depending on {@code scope})
 * @param scope       whether {@code attributeId} refers to a type-level attribute or to an
 *                    organization-level attribute. {@code null} is treated as {@link
 *                    AttributeScope#TYPE} so legacy callers keep working unchanged.
 * @param value       raw user input; will be validated and normalized by the matching strategy
 */
public record AttributeValueInputDto(Integer attributeId, AttributeScope scope, String value) {

    /**
     * Convenience accessor returning {@link AttributeScope#TYPE} when {@code scope} was omitted.
     */
    public AttributeScope effectiveScope() {
        return scope == null ? AttributeScope.TYPE : scope;
    }
}
