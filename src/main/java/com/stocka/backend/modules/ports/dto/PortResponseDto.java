package com.stocka.backend.modules.ports.dto;

import java.util.List;
import java.util.Map;

import com.stocka.backend.modules.piecetypes.dto.ActionParameterDto;
import com.stocka.backend.modules.ports.entity.Port;

/**
 * Single port exposed in REST responses. {@code kind} is the catalog id ({@code led_segment}...),
 * {@code config} the kind-specific keys (never including {@code pin}, which has its own field),
 * {@code pieceTypeName} the resolved display name of the related piece type ({@code null} for
 * infrastructure ports or when the type has since been deleted), and {@code parameters} the legacy
 * typed-parameter list.
 */
public record PortResponseDto(
        Integer id,
        String name,
        String kind,
        Integer pieceTypeId,
        String pieceTypeName,
        Integer pin,
        Map<String, Object> config,
        int position,
        List<ActionParameterDto> parameters
) {
    /**
     * Builds the response from a {@link Port} and its already-deserialized blobs.
     *
     * @param port          source port
     * @param config        deserialized config object
     * @param parameters    deserialized legacy parameter list
     * @param pieceTypeName resolved name of the related piece type, or {@code null}
     * @return the populated response DTO
     */
    public static PortResponseDto from(Port port, Map<String, Object> config, List<ActionParameterDto> parameters,
                                       String pieceTypeName) {
        return new PortResponseDto(
                port.getId(),
                port.getName(),
                port.getKind() == null ? null : port.getKind().catalogId(),
                port.getPieceTypeId(),
                pieceTypeName,
                port.getPin(),
                config,
                port.getPosition(),
                parameters
        );
    }
}
