package com.stocka.backend.modules.piecetypes.dto;

/**
 * PATCH-partial payload for updating one attribute of a piece type. {@code null} means
 * "leave unchanged"; {@code type} cannot be changed once the attribute exists (would invalidate
 * stored values), so it is intentionally not present.
 */
public class UpdatePieceTypeAttributeDto {
    private String displayName;
    private Boolean required;
    private Integer position;
    private AttributeValidatorsDto validators;

    public String getDisplayName() { return displayName; }
    public UpdatePieceTypeAttributeDto setDisplayName(String v) { this.displayName = v; return this; }

    public Boolean getRequired() { return required; }
    public UpdatePieceTypeAttributeDto setRequired(Boolean v) { this.required = v; return this; }

    public Integer getPosition() { return position; }
    public UpdatePieceTypeAttributeDto setPosition(Integer v) { this.position = v; return this; }

    public AttributeValidatorsDto getValidators() { return validators; }
    public UpdatePieceTypeAttributeDto setValidators(AttributeValidatorsDto v) { this.validators = v; return this; }
}
