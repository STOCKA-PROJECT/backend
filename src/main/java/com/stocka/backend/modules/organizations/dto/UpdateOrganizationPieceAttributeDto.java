package com.stocka.backend.modules.organizations.dto;

import com.stocka.backend.modules.piecetypes.dto.AttributeValidatorsDto;
import com.stocka.backend.modules.piecetypes.entity.AttributeType;

/**
 * PATCH-partial payload for updating one organization-level piece attribute. {@code null} means
 * "leave unchanged".
 *
 * <p>{@code name} (technical identifier) and {@code type} are mutable in edit mode. Renaming
 * preserves stored values and does not rewrite past
 * {@link com.stocka.backend.modules.pieces.entity.PieceHistory} entries. Changing {@code type} is
 * only accepted when no piece holds an active value for the attribute; otherwise the service
 * rejects the request with HTTP 409.
 */
public class UpdateOrganizationPieceAttributeDto {
    private String name;
    private String displayName;
    private AttributeType type;
    private Boolean required;
    private Integer position;
    private AttributeValidatorsDto validators;

    public String getName() { return name; }
    public UpdateOrganizationPieceAttributeDto setName(String v) { this.name = v; return this; }

    public String getDisplayName() { return displayName; }
    public UpdateOrganizationPieceAttributeDto setDisplayName(String v) { this.displayName = v; return this; }

    public AttributeType getType() { return type; }
    public UpdateOrganizationPieceAttributeDto setType(AttributeType v) { this.type = v; return this; }

    public Boolean getRequired() { return required; }
    public UpdateOrganizationPieceAttributeDto setRequired(Boolean v) { this.required = v; return this; }

    public Integer getPosition() { return position; }
    public UpdateOrganizationPieceAttributeDto setPosition(Integer v) { this.position = v; return this; }

    public AttributeValidatorsDto getValidators() { return validators; }
    public UpdateOrganizationPieceAttributeDto setValidators(AttributeValidatorsDto v) { this.validators = v; return this; }
}
