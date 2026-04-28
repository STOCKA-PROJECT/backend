package com.stocka.backend.modules.pieces.dto;

/**
 * One attribute value sent by the client when creating or updating a piece.
 *
 * @param attributeId id of the {@code piece_type_attributes} row this value targets
 * @param value       raw user input; will be validated and normalized by the matching strategy
 */
public record AttributeValueInputDto(Integer attributeId, String value) {
}
