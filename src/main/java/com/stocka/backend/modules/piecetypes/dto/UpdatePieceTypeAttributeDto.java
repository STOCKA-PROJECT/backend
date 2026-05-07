package com.stocka.backend.modules.piecetypes.dto;

import com.stocka.backend.modules.piecetypes.entity.AttributeType;

/**
 * PATCH-partial payload for updating one attribute of a piece type. {@code null} means
 * "leave unchanged".
 *
 * <p>{@code name} (technical identifier) and {@code type} are mutable in edit mode. Renaming
 * preserves stored values (the FK is by id, not by name) and does not rewrite past
 * {@code PieceHistory} entries. Changing {@code type} is only accepted when no piece holds an
 * active value for the attribute; otherwise the service rejects the request with HTTP 409.
 */
public class UpdatePieceTypeAttributeDto {
    private String name;
    private String displayName;
    private AttributeType type;
    private Boolean required;
    private Integer position;
    private AttributeValidatorsDto validators;

    public String getName() { return name; }
    public UpdatePieceTypeAttributeDto setName(String v) { this.name = v; return this; }

    public String getDisplayName() { return displayName; }
    public UpdatePieceTypeAttributeDto setDisplayName(String v) { this.displayName = v; return this; }

    public AttributeType getType() { return type; }
    public UpdatePieceTypeAttributeDto setType(AttributeType v) { this.type = v; return this; }

    public Boolean getRequired() { return required; }
    public UpdatePieceTypeAttributeDto setRequired(Boolean v) { this.required = v; return this; }

    public Integer getPosition() { return position; }
    public UpdatePieceTypeAttributeDto setPosition(Integer v) { this.position = v; return this; }

    public AttributeValidatorsDto getValidators() { return validators; }
    public UpdatePieceTypeAttributeDto setValidators(AttributeValidatorsDto v) { this.validators = v; return this; }
}
