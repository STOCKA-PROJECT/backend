package com.stocka.backend.modules.pieces.dto;

import java.util.List;

/**
 * PATCH-partial payload. {@code null} for the simple fields means "leave unchanged".
 *
 * <p>For owner/location detachment use the dedicated booleans
 * ({@link #setClearOwner(Boolean)} / {@link #setClearLocation(Boolean)}) so {@code null} can be
 * unambiguously interpreted as "do not touch".
 *
 * <p>{@code pieceTypeIds}, when not {@code null}, replaces the full set of types attached to the
 * piece. The list must contain at least one id. Removing a type also removes any attribute
 * values for attributes that exclusively belonged to it; the resulting status is recomputed.
 *
 * <p>{@code attributeValues}, when present, replaces the values for the listed attributes (other
 * attributes' values are kept as-is). Sending {@code value=null}/empty for an attribute clears it.
 */
public class UpdatePieceDto {
    private String name;
    private String description;
    private List<Integer> pieceTypeIds;
    private Integer ownerUserId;
    private Boolean clearOwner;
    private Integer locationId;
    private Boolean clearLocation;
    private List<AttributeValueInputDto> attributeValues;

    public String getName() { return name; }
    public UpdatePieceDto setName(String v) { this.name = v; return this; }

    public String getDescription() { return description; }
    public UpdatePieceDto setDescription(String v) { this.description = v; return this; }

    public List<Integer> getPieceTypeIds() { return pieceTypeIds; }
    public UpdatePieceDto setPieceTypeIds(List<Integer> v) { this.pieceTypeIds = v; return this; }

    public Integer getOwnerUserId() { return ownerUserId; }
    public UpdatePieceDto setOwnerUserId(Integer v) { this.ownerUserId = v; return this; }

    public Boolean getClearOwner() { return clearOwner; }
    public UpdatePieceDto setClearOwner(Boolean v) { this.clearOwner = v; return this; }

    public Integer getLocationId() { return locationId; }
    public UpdatePieceDto setLocationId(Integer v) { this.locationId = v; return this; }

    public Boolean getClearLocation() { return clearLocation; }
    public UpdatePieceDto setClearLocation(Boolean v) { this.clearLocation = v; return this; }

    public List<AttributeValueInputDto> getAttributeValues() { return attributeValues; }
    public UpdatePieceDto setAttributeValues(List<AttributeValueInputDto> v) { this.attributeValues = v; return this; }
}
