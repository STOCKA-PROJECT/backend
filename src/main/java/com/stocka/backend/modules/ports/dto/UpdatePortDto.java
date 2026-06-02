package com.stocka.backend.modules.ports.dto;

import java.util.List;

import com.stocka.backend.modules.piecetypes.dto.ActionParameterDto;

/**
 * Partial update payload for a port. Every field is nullable; a {@code null} field means "leave
 * unchanged". A non-null {@code parameters} list replaces the existing parameter set wholesale.
 */
public class UpdatePortDto {
    private String name;
    private Integer pieceTypeId;
    private Integer pin;
    private List<ActionParameterDto> parameters;

    public String getName() { return name; }
    public UpdatePortDto setName(String v) { this.name = v; return this; }

    public Integer getPieceTypeId() { return pieceTypeId; }
    public UpdatePortDto setPieceTypeId(Integer v) { this.pieceTypeId = v; return this; }

    public Integer getPin() { return pin; }
    public UpdatePortDto setPin(Integer v) { this.pin = v; return this; }

    public List<ActionParameterDto> getParameters() { return parameters; }
    public UpdatePortDto setParameters(List<ActionParameterDto> v) { this.parameters = v; return this; }
}
