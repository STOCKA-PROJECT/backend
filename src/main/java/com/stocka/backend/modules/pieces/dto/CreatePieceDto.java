package com.stocka.backend.modules.pieces.dto;

import java.util.List;

/**
 * Body of {@code POST /organizations/{orgId}/pieces}.
 *
 * <p>{@code pieceTypeIds} may contain zero, one or many type ids; the piece inherits the union of
 * all those types' attributes. {@code attributeValues} may omit attributes — required ones that
 * are not provided make the piece end up in {@code PENDING}. Sending {@code value=null} or an
 * empty string is equivalent to "not provided".
 *
 * <p>{@code serialNumber} is optional but, when provided, must be unique within the organization
 * (case-sensitive, comparing against non-deleted pieces). Empty/blank is treated as "not provided".
 *
 * <p>The cover image cannot be set at creation time because the piece does not yet have any
 * attachments. The first IMAGE attachment uploaded after creation is auto-marked as cover.
 */
public class CreatePieceDto {
    private String name;
    private String serialNumber;
    private String description;
    private List<Integer> pieceTypeIds;
    private Integer ownerUserId;
    private Integer locationId;
    private List<AttributeValueInputDto> attributeValues;

    public String getName() { return name; }
    public CreatePieceDto setName(String v) { this.name = v; return this; }

    public String getSerialNumber() { return serialNumber; }
    public CreatePieceDto setSerialNumber(String v) { this.serialNumber = v; return this; }

    public String getDescription() { return description; }
    public CreatePieceDto setDescription(String v) { this.description = v; return this; }

    public List<Integer> getPieceTypeIds() { return pieceTypeIds; }
    public CreatePieceDto setPieceTypeIds(List<Integer> v) { this.pieceTypeIds = v; return this; }

    public Integer getOwnerUserId() { return ownerUserId; }
    public CreatePieceDto setOwnerUserId(Integer v) { this.ownerUserId = v; return this; }

    public Integer getLocationId() { return locationId; }
    public CreatePieceDto setLocationId(Integer v) { this.locationId = v; return this; }

    public List<AttributeValueInputDto> getAttributeValues() { return attributeValues; }
    public CreatePieceDto setAttributeValues(List<AttributeValueInputDto> v) { this.attributeValues = v; return this; }
}
