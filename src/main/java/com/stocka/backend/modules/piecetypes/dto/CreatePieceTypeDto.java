package com.stocka.backend.modules.piecetypes.dto;

import java.util.List;

/**
 * Payload for {@code POST /organizations/{orgId}/piece-types}. The {@code attributes} list may be
 * empty; attributes can be added later through the dedicated endpoints.
 */
public class CreatePieceTypeDto {
    private String name;
    private List<CreatePieceTypeAttributeDto> attributes;

    public String getName() { return name; }
    public CreatePieceTypeDto setName(String v) { this.name = v; return this; }

    public List<CreatePieceTypeAttributeDto> getAttributes() { return attributes; }
    public CreatePieceTypeDto setAttributes(List<CreatePieceTypeAttributeDto> v) { this.attributes = v; return this; }
}
