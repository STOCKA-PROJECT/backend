package com.stocka.backend.modules.pieces.service;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.stocka.backend.modules.common.error.ApiException;
import com.stocka.backend.modules.common.error.ErrorCodes;
import com.stocka.backend.modules.pieces.dto.AttributeScope;
import com.stocka.backend.modules.pieces.dto.PieceFilterCriteria;
import com.stocka.backend.modules.pieces.dto.PieceFilterCriteria.AttributeFilter;
import com.stocka.backend.modules.pieces.entity.PieceStatus;

/**
 * Translates the raw piece-list query parameters into a {@link PieceFilterCriteria}. Shared by
 * the list and export endpoints so both accept exactly the same filter syntax.
 *
 * <p>Attribute filters arrive as repeatable {@code attr} parameters encoded as
 * {@code <scope>:<attributeId>:<v1>|<v2>|...} where each value token is individually
 * percent-encoded by the client (so literal {@code :}, {@code |} and {@code %} never collide
 * with the separators). This parser only checks syntax and defensive limits; semantic
 * validation (attribute existence, org ownership, per-type value shape) happens in
 * {@link PieceService}.
 */
@Component
public class PieceFilterParamParser {
    private static final int MAX_ATTRIBUTE_FILTERS = 20;
    private static final int MAX_VALUES_PER_FILTER = 50;
    private static final int MAX_VALUE_LENGTH = 500;

    /**
     * Builds the filter criteria from the raw request parameters.
     *
     * @param typeId  legacy single piece-type filter, merged into {@code typeIds}
     * @param typeIds repeatable piece-type filter (OR semantics)
     * @param locationId optional exact location filter
     * @param ownerUserId optional member-owner filter
     * @param ownerContactId optional contact-owner filter
     * @param status optional status filter
     * @param q optional name/description search
     * @param attrParams repeatable {@code attr} parameters, may be {@code null}
     * @return the parsed criteria
     * @throws ApiException 400 ({@code pieces.filter.invalid}) when any {@code attr} parameter is
     *         malformed or a defensive limit is exceeded
     */
    public PieceFilterCriteria parse(
            Integer typeId,
            List<Integer> typeIds,
            Integer locationId,
            Integer ownerUserId,
            Integer ownerContactId,
            PieceStatus status,
            String q,
            List<String> attrParams
    ) {
        Set<Integer> mergedTypeIds = new LinkedHashSet<>();
        if (typeIds != null) {
            typeIds.stream().filter(id -> id != null).forEach(mergedTypeIds::add);
        }
        if (typeId != null) {
            mergedTypeIds.add(typeId);
        }
        return new PieceFilterCriteria(
                List.copyOf(mergedTypeIds),
                locationId,
                ownerUserId,
                ownerContactId,
                status,
                q,
                parseAttributeFilters(attrParams)
        );
    }

    private List<AttributeFilter> parseAttributeFilters(List<String> attrParams) {
        if (attrParams == null || attrParams.isEmpty()) {
            return List.of();
        }
        if (attrParams.size() > MAX_ATTRIBUTE_FILTERS) {
            throw invalid("too many attribute filters (max " + MAX_ATTRIBUTE_FILTERS + ")");
        }
        List<AttributeFilter> filters = new ArrayList<>(attrParams.size());
        for (String raw : attrParams) {
            filters.add(parseOne(raw));
        }
        return List.copyOf(filters);
    }

    private AttributeFilter parseOne(String raw) {
        if (raw == null || raw.isBlank()) {
            throw invalid("empty attr parameter");
        }
        String[] parts = raw.split(":", 3);
        if (parts.length != 3) {
            throw invalid("expected <scope>:<attributeId>:<values>: " + raw);
        }
        AttributeScope scope;
        try {
            scope = AttributeScope.valueOf(parts[0]);
        } catch (IllegalArgumentException e) {
            throw invalid("unknown scope: " + parts[0]);
        }
        int attributeId;
        try {
            attributeId = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw invalid("attributeId must be a number: " + parts[1]);
        }
        String[] tokens = parts[2].split("\\|", -1);
        if (tokens.length > MAX_VALUES_PER_FILTER) {
            throw invalid("too many values (max " + MAX_VALUES_PER_FILTER + ")");
        }
        List<String> values = new ArrayList<>(tokens.length);
        boolean anyNonBlank = false;
        for (String token : tokens) {
            String decoded;
            try {
                decoded = URLDecoder.decode(token, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                throw invalid("bad percent-encoding in value: " + token);
            }
            if (decoded.length() > MAX_VALUE_LENGTH) {
                throw invalid("value too long (max " + MAX_VALUE_LENGTH + " chars)");
            }
            anyNonBlank |= !decoded.isBlank();
            values.add(decoded);
        }
        if (!anyNonBlank) {
            throw invalid("attribute filter has no values: " + raw);
        }
        return new AttributeFilter(scope, attributeId, List.copyOf(values));
    }

    private static ApiException invalid(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.PIECES_FILTER_INVALID,
                Map.of("detail", detail));
    }
}
