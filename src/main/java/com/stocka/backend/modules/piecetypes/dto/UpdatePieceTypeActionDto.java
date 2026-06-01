package com.stocka.backend.modules.piecetypes.dto;

import java.util.List;

/**
 * PATCH-partial payload for updating one action of a piece type. A {@code null} field means
 * "leave unchanged". When {@code parameters} is non-null it replaces the whole parameter list.
 */
public class UpdatePieceTypeActionDto {
    private String name;
    private String displayName;
    private String description;
    private Integer position;
    private List<ActionParameterDto> parameters;

    public String getName() { return name; }
    public UpdatePieceTypeActionDto setName(String v) { this.name = v; return this; }

    public String getDisplayName() { return displayName; }
    public UpdatePieceTypeActionDto setDisplayName(String v) { this.displayName = v; return this; }

    public String getDescription() { return description; }
    public UpdatePieceTypeActionDto setDescription(String v) { this.description = v; return this; }

    public Integer getPosition() { return position; }
    public UpdatePieceTypeActionDto setPosition(Integer v) { this.position = v; return this; }

    public List<ActionParameterDto> getParameters() { return parameters; }
    public UpdatePieceTypeActionDto setParameters(List<ActionParameterDto> v) { this.parameters = v; return this; }
}
