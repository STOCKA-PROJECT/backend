package com.stocka.backend.modules.pieces.dto;

import com.stocka.backend.modules.piecetypes.entity.PieceType;

/**
 * Compact reference to a {@code PieceType}: id, display name and the output kind it drives
 * (catalog id such as {@code led_strip}, or {@code null} for inventory-only types). The kind lets
 * the timeline editor pick catalog-aware inputs (e.g. a colour picker for {@code rgb} parameters).
 */
public record PieceTypeRefDto(Integer id, String name, String kind) {
    public static PieceTypeRefDto from(PieceType type) {
        return new PieceTypeRefDto(type.getId(), type.getName(),
                type.getKind() == null ? null : type.getKind().catalogId());
    }
}
