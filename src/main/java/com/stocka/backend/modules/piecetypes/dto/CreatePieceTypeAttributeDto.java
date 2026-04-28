package com.stocka.backend.modules.piecetypes.dto;

import com.stocka.backend.modules.piecetypes.entity.AttributeType;

/**
 * Single attribute included when creating a piece type, or as standalone payload for adding a new
 * attribute to an existing type.
 */
public class CreatePieceTypeAttributeDto {
    private String name;
    private String displayName;
    private AttributeType type;
    private Boolean required;
    private Integer position;
    private AttributeValidatorsDto validators;

    public String getName() { return name; }
    public CreatePieceTypeAttributeDto setName(String v) { this.name = v; return this; }

    public String getDisplayName() { return displayName; }
    public CreatePieceTypeAttributeDto setDisplayName(String v) { this.displayName = v; return this; }

    public AttributeType getType() { return type; }
    public CreatePieceTypeAttributeDto setType(AttributeType v) { this.type = v; return this; }

    public Boolean getRequired() { return required; }
    public CreatePieceTypeAttributeDto setRequired(Boolean v) { this.required = v; return this; }

    public Integer getPosition() { return position; }
    public CreatePieceTypeAttributeDto setPosition(Integer v) { this.position = v; return this; }

    public AttributeValidatorsDto getValidators() { return validators; }
    public CreatePieceTypeAttributeDto setValidators(AttributeValidatorsDto v) { this.validators = v; return this; }
}
