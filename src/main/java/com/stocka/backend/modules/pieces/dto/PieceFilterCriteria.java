package com.stocka.backend.modules.pieces.dto;

import java.util.List;

import com.stocka.backend.modules.pieces.entity.PieceStatus;

/**
 * Immutable set of filters accepted by the piece list and export queries. Groups the legacy
 * scalar filters together with the advanced ones (multiple piece types, per-attribute filters)
 * so the controllers, the service and the export module share a single contract.
 *
 * <p>Semantics: pieces must match every non-null filter (AND). Within {@link #typeIds} a piece
 * matches when it has <em>any</em> of the given types (OR). Within one {@link AttributeFilter}
 * the values are OR-ed; distinct attribute filters are AND-ed.
 *
 * @param typeIds          piece-type ids; empty means no type filter
 * @param locationId       optional exact location filter
 * @param ownerUserId      optional member-owner filter
 * @param ownerContactId   optional contact-owner filter
 * @param status           optional status filter
 * @param q                optional name/description search
 * @param attributeFilters advanced per-attribute filters; empty means none
 */
public record PieceFilterCriteria(
        List<Integer> typeIds,
        Integer locationId,
        Integer ownerUserId,
        Integer ownerContactId,
        PieceStatus status,
        String q,
        List<AttributeFilter> attributeFilters
) {
    /**
     * One filter over a single custom attribute (type-level or organization-level).
     *
     * <p>How {@link #values} is interpreted depends on the attribute's
     * {@code AttributeType}, resolved by the service:
     * <ul>
     *   <li>SELECT / BOOLEAN / MEMBER — canonical values matched exactly, OR-ed.</li>
     *   <li>MULTI_SELECT — "contains any of" over the stored JSON array.</li>
     *   <li>TEXT / LONGTEXT / URL / EMAIL — case-insensitive "contains" terms, OR-ed.</li>
     *   <li>INTEGER / DECIMAL / PRICE / DATE / DATETIME — exactly two tokens
     *       {@code [min, max]}; a blank token means that bound is open.</li>
     * </ul>
     *
     * @param scope       whether {@link #attributeId} references a type-level or org-level attribute
     * @param attributeId id of the attribute in the scope's table
     * @param values      raw filter tokens, already URL-decoded
     */
    public record AttributeFilter(AttributeScope scope, Integer attributeId, List<String> values) {}

    /**
     * Creates a criteria with no filters at all.
     *
     * @return an empty criteria instance
     */
    public static PieceFilterCriteria empty() {
        return new PieceFilterCriteria(List.of(), null, null, null, null, null, List.of());
    }
}
