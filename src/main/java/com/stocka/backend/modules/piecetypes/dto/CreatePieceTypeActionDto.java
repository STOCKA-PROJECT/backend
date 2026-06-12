package com.stocka.backend.modules.piecetypes.dto;

import java.util.List;

/**
 * Payload for declaring a new action on a piece type, with its ordered list of typed parameters.
 */
public class CreatePieceTypeActionDto {
    private String name;
    private String displayName;
    private String description;
    private Integer position;
    private List<ActionParameterDto> parameters;

    public String getName() { return name; }
    public CreatePieceTypeActionDto setName(String v) { this.name = v; return this; }

    public String getDisplayName() { return displayName; }
    public CreatePieceTypeActionDto setDisplayName(String v) { this.displayName = v; return this; }

    public String getDescription() { return description; }
    public CreatePieceTypeActionDto setDescription(String v) { this.description = v; return this; }

    public Integer getPosition() { return position; }
    public CreatePieceTypeActionDto setPosition(Integer v) { this.position = v; return this; }

    public List<ActionParameterDto> getParameters() { return parameters; }
    public CreatePieceTypeActionDto setParameters(List<ActionParameterDto> v) { this.parameters = v; return this; }
}
