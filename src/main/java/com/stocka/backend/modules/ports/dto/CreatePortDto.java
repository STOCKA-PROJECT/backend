package com.stocka.backend.modules.ports.dto;

import java.util.List;

import com.stocka.backend.modules.piecetypes.dto.ActionParameterDto;

/**
 * Payload for declaring a new port, related to an existing piece type ({@code pieceTypeId}), with
 * its Raspberry Pi GPIO pin and ordered list of typed parameters.
 */
public class CreatePortDto {
    private String name;
    private Integer pieceTypeId;
    private Integer pin;
    private List<ActionParameterDto> parameters;

    public String getName() { return name; }
    public CreatePortDto setName(String v) { this.name = v; return this; }

    public Integer getPieceTypeId() { return pieceTypeId; }
    public CreatePortDto setPieceTypeId(Integer v) { this.pieceTypeId = v; return this; }

    public Integer getPin() { return pin; }
    public CreatePortDto setPin(Integer v) { this.pin = v; return this; }

    public List<ActionParameterDto> getParameters() { return parameters; }
    public CreatePortDto setParameters(List<ActionParameterDto> v) { this.parameters = v; return this; }
}
