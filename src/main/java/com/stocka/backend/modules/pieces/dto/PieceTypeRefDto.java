package com.stocka.backend.modules.pieces.dto;

import com.stocka.backend.modules.piecetypes.entity.PieceType;

/** Compact reference to a {@code PieceType}: id and display name. */
public record PieceTypeRefDto(Integer id, String name) {
    public static PieceTypeRefDto from(PieceType type) {
        return new PieceTypeRefDto(type.getId(), type.getName());
    }
}
