package com.stocka.backend.modules.ports.dto;

import java.util.List;

import com.stocka.backend.modules.piecetypes.dto.ActionParameterDto;
import com.stocka.backend.modules.ports.entity.Port;

/**
 * Single port exposed in REST responses, with its raw {@code parameters_json} blob deserialized
 * back into the strongly-typed {@link ActionParameterDto} list. {@code pieceTypeName} is the
 * resolved display name of the related piece type ({@code null} if it has since been deleted).
 */
public record PortResponseDto(
        Integer id,
        String name,
        Integer pieceTypeId,
        String pieceTypeName,
        Integer pin,
        int position,
        List<ActionParameterDto> parameters
) {
    /**
     * Builds the response from a {@link Port}, its already-deserialized parameter list and the
     * resolved piece-type display name.
     *
     * @param port          source port
     * @param parameters    deserialized parameter list
     * @param pieceTypeName resolved name of the related piece type, or {@code null}
     * @return the populated response DTO
     */
    public static PortResponseDto from(Port port, List<ActionParameterDto> parameters, String pieceTypeName) {
        return new PortResponseDto(
                port.getId(),
                port.getName(),
                port.getPieceTypeId(),
                pieceTypeName,
                port.getPin(),
                port.getPosition(),
                parameters
        );
    }
}
