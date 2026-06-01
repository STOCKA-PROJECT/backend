package com.stocka.backend.modules.piecetypes.dto;

import java.util.List;

import com.stocka.backend.modules.piecetypes.entity.PieceTypeAction;

/**
 * Single action exposed in REST responses, with its raw {@code parameters_json} blob deserialized
 * back into the strongly-typed {@link ActionParameterDto} list.
 */
public record PieceTypeActionResponseDto(
        Integer id,
        String name,
        String displayName,
        String description,
        int position,
        List<ActionParameterDto> parameters
) {
    public static PieceTypeActionResponseDto from(PieceTypeAction action, List<ActionParameterDto> parameters) {
        return new PieceTypeActionResponseDto(
                action.getId(),
                action.getName(),
                action.getDisplayName(),
                action.getDescription(),
                action.getPosition(),
                parameters
        );
    }
}
