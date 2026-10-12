package com.stocka.backend.modules.piecetypes.dto;

/**
 * PATCH-partial payload for a piece type. {@code null} leaves a field unchanged. {@code kind} sets
 * the output kind (catalog id); changing it re-validates the type's actions against the new kind and
 * is refused while ports of another kind reference the type. Clearing a kind is not supported.
 */
public class UpdatePieceTypeDto {
    private String name;
    private String kind;

    public String getName() { return name; }
    public UpdatePieceTypeDto setName(String v) { this.name = v; return this; }

    public String getKind() { return kind; }
    public UpdatePieceTypeDto setKind(String v) { this.kind = v; return this; }
}
