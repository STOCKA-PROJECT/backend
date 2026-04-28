package com.stocka.backend.modules.piecetypes.dto;

/**
 * PATCH-partial payload for renaming a piece type. {@code null} leaves the field unchanged.
 */
public class UpdatePieceTypeDto {
    private String name;

    public String getName() { return name; }
    public UpdatePieceTypeDto setName(String v) { this.name = v; return this; }
}
