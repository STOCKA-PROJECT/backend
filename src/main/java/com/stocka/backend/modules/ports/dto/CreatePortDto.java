package com.stocka.backend.modules.ports.dto;

import java.util.List;
import java.util.Map;

import com.stocka.backend.modules.piecetypes.dto.ActionParameterDto;

/**
 * Payload for declaring a new port.
 *
 * <p>{@code kind} is the catalog id of the output kind ({@code relay}, {@code led_strip},
 * {@code led_segment}, {@code motor}, {@code audio}, {@code video}, {@code button}) and is required.
 * {@code pieceTypeId} is required for every kind except the infrastructure ones (strips, button);
 * the referenced type must have the same kind. {@code pin} is required for kinds that have a GPIO
 * pin and must be absent otherwise. {@code config} holds the kind-specific keys (DMA and length of a
 * strip, strip and range of a segment, the three pins of a motor...) and is validated against the
 * catalog. {@code parameters} is the legacy typed-parameter list and may be omitted.
 */
public class CreatePortDto {
    private String name;
    private String kind;
    private Integer pieceTypeId;
    private Integer pin;
    private Map<String, Object> config;
    private List<ActionParameterDto> parameters;

    public String getName() { return name; }
    public CreatePortDto setName(String v) { this.name = v; return this; }

    public String getKind() { return kind; }
    public CreatePortDto setKind(String v) { this.kind = v; return this; }

    public Integer getPieceTypeId() { return pieceTypeId; }
    public CreatePortDto setPieceTypeId(Integer v) { this.pieceTypeId = v; return this; }

    public Integer getPin() { return pin; }
    public CreatePortDto setPin(Integer v) { this.pin = v; return this; }

    public Map<String, Object> getConfig() { return config; }
    public CreatePortDto setConfig(Map<String, Object> v) { this.config = v; return this; }

    public List<ActionParameterDto> getParameters() { return parameters; }
    public CreatePortDto setParameters(List<ActionParameterDto> v) { this.parameters = v; return this; }
}
