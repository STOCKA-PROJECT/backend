package com.stocka.backend.modules.sync.dto;

/**
 * One embedded attribute value of a piece in the sync feed. The attribute is referenced by its
 * {@code syncId} (of the type-level or organization-level attribute) so offline clients resolve it
 * without numeric ids.
 *
 * @param attributeSyncId sync id of the attribute definition
 * @param value           normalized stored value (may be {@code null})
 * @since 0.2.0
 */
public record AttributeValueSyncDto(String attributeSyncId, String value) {
}
