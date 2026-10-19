package com.stocka.backend.modules.timelines.show;

import java.util.LinkedHashMap;
import java.util.Map;

import com.stocka.backend.modules.ports.entity.Port;

/**
 * A hardware port as the show builder sees it: the fields of the editor's {@code PortResponseDto}
 * that reach the package. Mirrors {@code ExecutionPort} in {@code utils/exportBundle.ts}.
 *
 * @param id          port id
 * @param name        port name (unique per organization; the show target)
 * @param kind        catalog id of the kind ({@code relay}, {@code led_strip}...)
 * @param pieceTypeId piece type the port belongs to; {@code null} for infrastructure ports
 * @param pin         GPIO pin for kinds that have one; {@code null} otherwise
 * @param config      kind-specific configuration keys, never {@code null}
 * @param position    order among the organization's ports
 */
public record ShowPort(
        int id,
        String name,
        String kind,
        Integer pieceTypeId,
        Integer pin,
        Map<String, Object> config,
        int position) {

    public ShowPort {
        config = config == null ? new LinkedHashMap<>() : new LinkedHashMap<>(config);
    }

    /**
     * @param port   the persisted port
     * @param config its deserialized configuration
     * @return the show view of the port
     */
    public static ShowPort from(Port port, Map<String, Object> config) {
        return new ShowPort(port.getId(), port.getName(), port.getKind() == null ? null : port.getKind().catalogId(),
                port.getPieceTypeId(), port.getPin(), config, port.getPosition());
    }
}
