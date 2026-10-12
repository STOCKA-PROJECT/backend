package com.stocka.backend.modules.ports.dto;

import java.util.List;
import java.util.Map;

import com.stocka.backend.modules.piecetypes.dto.ActionParameterDto;

/**
 * Partial update payload for a port. Every field is nullable; a {@code null} field means "leave
 * unchanged". A non-null {@code config} object replaces the whole config and is re-validated; a
 * non-null {@code parameters} list replaces the legacy parameter set wholesale. {@code kind} may be
 * sent only with the port's current value: the kind of a port is immutable.
 */
public class UpdatePortDto {
    private String name;
    private String kind;
    private Integer pieceTypeId;
    private Integer pin;
    private Map<String, Object> config;
    private List<ActionParameterDto> parameters;

    public String getName() { return name; }
    public UpdatePortDto setName(String v) { this.name = v; return this; }

    public String getKind() { return kind; }
    public UpdatePortDto setKind(String v) { this.kind = v; return this; }

    public Integer getPieceTypeId() { return pieceTypeId; }
    public UpdatePortDto setPieceTypeId(Integer v) { this.pieceTypeId = v; return this; }

    public Integer getPin() { return pin; }
    public UpdatePortDto setPin(Integer v) { this.pin = v; return this; }

    public Map<String, Object> getConfig() { return config; }
    public UpdatePortDto setConfig(Map<String, Object> v) { this.config = v; return this; }

    public List<ActionParameterDto> getParameters() { return parameters; }
    public UpdatePortDto setParameters(List<ActionParameterDto> v) { this.parameters = v; return this; }
}
