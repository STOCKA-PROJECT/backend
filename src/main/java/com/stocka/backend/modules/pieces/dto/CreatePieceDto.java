package com.stocka.backend.modules.pieces.dto;

import java.util.List;

/**
 * Body of {@code POST /organizations/{orgId}/pieces}.
 *
 * <p>{@code attributeValues} may omit attributes; missing required ones make the piece end up in
 * {@code PENDING}. Sending {@code value=null} or an empty string is equivalent to "not provided".
 */
public class CreatePieceDto {
    private String name;
    private String description;
    private Integer pieceTypeId;
    private Integer ownerUserId;
    private Integer locationId;
    private List<AttributeValueInputDto> attributeValues;

    public String getName() { return name; }
    public CreatePieceDto setName(String v) { this.name = v; return this; }

    public String getDescription() { return description; }
    public CreatePieceDto setDescription(String v) { this.description = v; return this; }

    public Integer getPieceTypeId() { return pieceTypeId; }
    public CreatePieceDto setPieceTypeId(Integer v) { this.pieceTypeId = v; return this; }

    public Integer getOwnerUserId() { return ownerUserId; }
    public CreatePieceDto setOwnerUserId(Integer v) { this.ownerUserId = v; return this; }

    public Integer getLocationId() { return locationId; }
    public CreatePieceDto setLocationId(Integer v) { this.locationId = v; return this; }

    public List<AttributeValueInputDto> getAttributeValues() { return attributeValues; }
    public CreatePieceDto setAttributeValues(List<AttributeValueInputDto> v) { this.attributeValues = v; return this; }
}
