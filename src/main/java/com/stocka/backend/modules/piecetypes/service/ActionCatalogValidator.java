package com.stocka.backend.modules.piecetypes.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import com.stocka.backend.modules.common.error.ApiException;
import com.stocka.backend.modules.common.error.ErrorCodes;
import com.stocka.backend.modules.contract.CatalogAction;
import com.stocka.backend.modules.contract.CatalogParam;
import com.stocka.backend.modules.contract.OutputCatalog;
import com.stocka.backend.modules.contract.OutputKind;
import com.stocka.backend.modules.piecetypes.dto.ActionParameterDto;

/**
 * Checks that a piece-type action is something the output catalog knows for the type's kind.
 *
 * <p>Applied only when the piece type has a kind. A separate component (rather than a method on
 * {@code PieceTypeService}) so that both {@code PieceTypeService} (kind changes) and
 * {@code PieceTypeActionService} (action writes) can use it without a circular dependency.
 */
@Component
public class ActionCatalogValidator {
    private static final String ANIMATION_TYPE = "animation_type";
    private static final Pattern HEX = Pattern.compile("^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$");
    private static final Pattern TRIPLE = Pattern.compile("^\\[?\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})\\s*\\]?$");
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final OutputCatalog catalog;

    public ActionCatalogValidator(OutputCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * Validates an action against the catalog of {@code kind}. No-op when {@code kind} is null.
     *
     * @param kind       kind of the owning piece type; {@code null} skips validation
     * @param actionName technical action name
     * @param params     normalized parameter list (never {@code null})
     * @throws ApiException 400 with a {@code piece_types.action_*} code on the first violation
     */
    public void validate(OutputKind kind, String actionName, List<ActionParameterDto> params) {
        if (kind == null) {
            return;
        }
        CatalogAction action = catalog.action(kind, actionName)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.PIECE_TYPES_ACTION_NOT_IN_CATALOG,
                        params("action", actionName == null ? "" : actionName, "kind", kind.catalogId())));

        String staticAnimation = staticAnimationOf(action, params);
        Map<String, CatalogParam> allowed = staticAnimation != null ? action.paramsFor(staticAnimation) : action.allParams();
        Optional<String> durationParam = action.durationParam();

        for (ActionParameterDto p : params) {
            CatalogParam spec = allowed.get(p.getName());
            if (spec == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.PIECE_TYPES_ACTION_PARAM_UNKNOWN,
                        params("param", p.getName(), "action", actionName));
            }
            if (!spec.type().accepts(p.getType())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.PIECE_TYPES_ACTION_PARAM_TYPE_MISMATCH,
                        params("param", p.getName(), "expected", spec.type().acceptedAttributeTypes()));
            }
            if (spec.type().name().equals("ENUM")) {
                List<String> options = p.getValidators() == null ? null : p.getValidators().getOptions();
                if (options == null || options.isEmpty() || !spec.values().containsAll(options)) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.PIECE_TYPES_ACTION_PARAM_OPTIONS_INVALID,
                            params("param", p.getName(), "values", String.join(", ", spec.values())));
                }
            }
            if (Boolean.TRUE.equals(p.getIsDuration()) && !spec.duration()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.PIECE_TYPES_ACTION_DURATION_PARAM_MISMATCH,
                        params("param", p.getName(), "expected", durationParam.orElse("-")));
            }
            if (!Boolean.TRUE.equals(p.getDynamic()) && p.getStaticValue() != null
                    && !staticValueFits(spec, p.getStaticValue())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.PIECE_TYPES_ACTION_PARAM_STATIC_INVALID,
                        params("param", p.getName()));
            }
        }

        Set<String> declared = new java.util.HashSet<>();
        params.forEach(p -> declared.add(p.getName()));
        for (CatalogParam spec : allowed.values()) {
            boolean requiredHere = spec.required()
                    && (action.params().containsKey(spec.name()) || staticAnimation != null);
            if (requiredHere && !declared.contains(spec.name())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.PIECE_TYPES_ACTION_PARAM_MISSING,
                        params("param", spec.name()));
            }
        }
    }

    /**
     * Whether a static value can be parsed as the catalog type and sits inside its bounds.
     *
     * @param spec  catalog parameter
     * @param value trimmed static value
     * @return {@code true} when it fits
     */
    static boolean staticValueFits(CatalogParam spec, String value) {
        String v = value.trim();
        return switch (spec.type()) {
            case INT -> parseDecimal(v).map(d -> d.stripTrailingZeros().scale() <= 0 && inBounds(spec, d)).orElse(false);
            case NUMBER -> parseDecimal(v).map(d -> inBounds(spec, d)).orElse(false);
            case BOOL -> v.equalsIgnoreCase("true") || v.equalsIgnoreCase("false");
            case ENUM -> spec.values().contains(v);
            case STRING -> !v.isEmpty();
            case RGB -> parseRgb(v) != null;
            case RGB_LIST -> parseRgbList(v) != null;
        };
    }

    /**
     * Parses a JSON array of colours, each written as a {@code [r,g,b]} array or as a string in
     * any notation {@link #parseRgb(String)} accepts (e.g. {@code [[255,0,0], "#00ff00"]}).
     *
     * @param value raw text
     * @return the colours (possibly none), or {@code null} when it is not such a list
     */
    static List<int[]> parseRgbList(String value) {
        JsonNode node;
        try {
            node = JSON.readTree(value.trim());
        } catch (JacksonException e) {
            return null;
        }
        if (node == null || !node.isArray()) {
            return null;
        }
        List<int[]> out = new ArrayList<>();
        for (JsonNode element : node) {
            int[] rgb = element.isArray() ? parseRgb(element.toString())
                    : element.isValueNode() ? parseRgb(element.asText("")) : null;
            if (rgb == null) {
                return null;
            }
            out.add(rgb);
        }
        return out;
    }

    /**
     * Parses a colour written as {@code [r,g,b]}, {@code r,g,b}, {@code #rrggbb} or {@code #rgb}.
     *
     * @param value raw text
     * @return the three components, or {@code null} when it is not a colour
     */
    static int[] parseRgb(String value) {
        Matcher hex = HEX.matcher(value.trim());
        if (hex.matches()) {
            String h = hex.group(1);
            if (h.length() == 3) {
                h = "" + h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2);
            }
            return new int[] {
                    Integer.parseInt(h.substring(0, 2), 16),
                    Integer.parseInt(h.substring(2, 4), 16),
                    Integer.parseInt(h.substring(4, 6), 16)};
        }
        Matcher triple = TRIPLE.matcher(value.trim());
        if (triple.matches()) {
            int[] out = new int[3];
            for (int i = 0; i < 3; i++) {
                out[i] = Integer.parseInt(triple.group(i + 1));
                if (out[i] > 255) {
                    return null;
                }
            }
            return out;
        }
        return null;
    }

    private static String staticAnimationOf(CatalogAction action, List<ActionParameterDto> params) {
        if (action.animations().isEmpty()) {
            return null;
        }
        return params.stream()
                .filter(p -> ANIMATION_TYPE.equals(p.getName()) && !Boolean.TRUE.equals(p.getDynamic()))
                .map(ActionParameterDto::getStaticValue)
                .filter(v -> v != null && action.hasAnimation(v.trim()))
                .map(String::trim)
                .findFirst()
                .orElse(null);
    }

    private static Optional<BigDecimal> parseDecimal(String v) {
        try {
            return Optional.of(new BigDecimal(v));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static boolean inBounds(CatalogParam spec, BigDecimal d) {
        return (spec.min() == null || d.compareTo(spec.min()) >= 0)
                && (spec.max() == null || d.compareTo(spec.max()) <= 0);
    }

    private static Map<String, Object> params(Object... kv) {
        Map<String, Object> p = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            p.put((String) kv[i], kv[i + 1]);
        }
        return p;
    }
}
