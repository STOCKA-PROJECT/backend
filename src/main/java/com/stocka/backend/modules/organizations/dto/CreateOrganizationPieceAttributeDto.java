package com.stocka.backend.modules.organizations.dto;

import com.stocka.backend.modules.piecetypes.dto.AttributeValidatorsDto;
import com.stocka.backend.modules.piecetypes.entity.AttributeType;

/**
 * Body of {@code POST /organizations/{orgId}/piece-attributes}. Mirrors
 * {@link com.stocka.backend.modules.piecetypes.dto.CreatePieceTypeAttributeDto} but applies to
 * every piece in the organization regardless of its type.
 */
public class CreateOrganizationPieceAttributeDto {
    private String name;
    private String displayName;
    private AttributeType type;
    private Boolean required;
    private Integer position;
    private AttributeValidatorsDto validators;

    public String getName() { return name; }
    public CreateOrganizationPieceAttributeDto setName(String v) { this.name = v; return this; }

    public String getDisplayName() { return displayName; }
    public CreateOrganizationPieceAttributeDto setDisplayName(String v) { this.displayName = v; return this; }

    public AttributeType getType() { return type; }
    public CreateOrganizationPieceAttributeDto setType(AttributeType v) { this.type = v; return this; }

    public Boolean getRequired() { return required; }
    public CreateOrganizationPieceAttributeDto setRequired(Boolean v) { this.required = v; return this; }

    public Integer getPosition() { return position; }
    public CreateOrganizationPieceAttributeDto setPosition(Integer v) { this.position = v; return this; }

    public AttributeValidatorsDto getValidators() { return validators; }
    public CreateOrganizationPieceAttributeDto setValidators(AttributeValidatorsDto v) { this.validators = v; return this; }
}
