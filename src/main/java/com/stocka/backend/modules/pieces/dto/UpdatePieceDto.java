package com.stocka.backend.modules.pieces.dto;

import java.util.List;

/**
 * PATCH-partial payload. {@code null} for the simple fields means "leave unchanged".
 *
 * <p>For owner/location/cover detachment use the dedicated booleans
 * ({@link #setClearOwner(Boolean)} / {@link #setClearLocation(Boolean)} /
 * {@link #setClearCover(Boolean)}) so {@code null} can be unambiguously interpreted as "do not
 * touch". For {@code serialNumber}, sending an empty string clears it (since blank is the canonical
 * "not provided" form for that field).
 *
 * <p>{@code pieceTypeIds}, when not {@code null}, replaces the full set of types attached to the
 * piece (the list may be empty to detach the piece from every type). Removing a type also
 * removes any attribute values for attributes that exclusively belonged to it; the resulting
 * status is recomputed.
 *
 * <p>{@code attributeValues}, when present, replaces the values for the listed attributes (other
 * attributes' values are kept as-is). Sending {@code value=null}/empty for an attribute clears it.
 *
 * <p>{@code coverAttachmentId} marks an existing IMAGE attachment of the same piece as cover.
 * It must reference an attachment that belongs to this piece, has {@code kind=IMAGE} and is not
 * soft-deleted.
 */
public class UpdatePieceDto {
    private String name;
    private String serialNumber;
    private String description;
    private List<Integer> pieceTypeIds;
    private Integer ownerUserId;
    private Boolean clearOwner;
    private Integer locationId;
    private Boolean clearLocation;
    private Integer coverAttachmentId;
    private Boolean clearCover;
    private List<AttributeValueInputDto> attributeValues;

    public String getName() { return name; }
    public UpdatePieceDto setName(String v) { this.name = v; return this; }

    public String getSerialNumber() { return serialNumber; }
    public UpdatePieceDto setSerialNumber(String v) { this.serialNumber = v; return this; }

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

    public Integer getCoverAttachmentId() { return coverAttachmentId; }
    public UpdatePieceDto setCoverAttachmentId(Integer v) { this.coverAttachmentId = v; return this; }

    public Boolean getClearCover() { return clearCover; }
    public UpdatePieceDto setClearCover(Boolean v) { this.clearCover = v; return this; }

    public List<AttributeValueInputDto> getAttributeValues() { return attributeValues; }
    public UpdatePieceDto setAttributeValues(List<AttributeValueInputDto> v) { this.attributeValues = v; return this; }
}
