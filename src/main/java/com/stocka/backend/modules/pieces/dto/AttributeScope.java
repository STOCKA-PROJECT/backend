package com.stocka.backend.modules.pieces.dto;

/**
 * Distinguishes whether an attribute reference targets a piece-type-level attribute
 * ({@link #TYPE}) or an organization-wide attribute ({@link #ORG}). Defaults to {@link #TYPE}
 * when omitted by the caller for backward compatibility.
 */
public enum AttributeScope {
    TYPE,
    ORG
}
