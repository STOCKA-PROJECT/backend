package com.stocka.backend.modules.timelines.scene;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.stocka.backend.modules.contract.CatalogAction;
import com.stocka.backend.modules.contract.CatalogParam;
import com.stocka.backend.modules.contract.OutputCatalog;
import com.stocka.backend.modules.contract.OutputKind;
import com.stocka.backend.modules.piecetypes.dto.ActionParameterDto;
import com.stocka.backend.modules.piecetypes.entity.AttributeType;
import com.stocka.backend.modules.timelines.scene.SceneContext.ActionRef;
import com.stocka.backend.modules.timelines.scene.SceneContext.PieceRef;
import com.stocka.backend.modules.timelines.scene.SceneContext.PortRef;
import com.stocka.backend.modules.timelines.scene.SceneDocument.Clip;
import com.stocka.backend.modules.timelines.scene.SceneDocument.Item;
import com.stocka.backend.modules.timelines.scene.SceneDocument.Track;

/**
 * Checks a scene the way the editor's export does, so problems surface before anyone opens the
 * editor: structure (ids, references), organization references (pieces, ports, actions), clip
 * parameters against the action definition and, for types with a hardware kind, against the output
 * catalog (types, ranges, enums, animation-specific parameters), plus timing warnings.
 */
public final class SceneValidator {

    private SceneValidator() {
    }

    /**
     * @param doc     the scene
     * @param context organization data
     * @param catalog output catalog (may be {@code null} to skip catalog checks)
     * @return every finding, errors first in document order
     */
    public static List<SceneIssue> validate(SceneDocument doc, SceneContext context, OutputCatalog catalog) {
        List<SceneIssue> issues = new ArrayList<>();
        validateStructure(doc, issues);
        Map<String, Item> items = new HashMap<>();
        doc.items().forEach(it -> items.put(it.id(), it));
        Map<String, Track> tracks = new HashMap<>();
        doc.tracks().forEach(t -> tracks.put(t.id(), t));
        Map<String, PieceRef> pieces = new HashMap<>();

        for (Item item : doc.items()) {
            Optional<PieceRef> piece = item.pieceId() == null ? Optional.empty() : context.pieceById(item.pieceId());
            if (piece.isEmpty()) {
                issues.add(SceneIssue.error("E_PIECE_MISSING", "Item " + item.id() + " references piece "
                        + item.pieceId() + ", which no longer exists.", item.id(), null));
                continue;
            }
            pieces.put(item.id(), piece.get());
            for (Integer portId : item.ports()) {
                Optional<PortRef> port = context.portById(portId);
                if (port.isEmpty()) {
                    issues.add(SceneIssue.warning("W_PORT_STALE", "Item " + item.id() + " is bound to port " + portId
                            + ", which no longer exists.", item.id(), null));
                    continue;
                }
                // Each role the port plays (portTypes, else its own type) must be a type of the piece, of the port's kind.
                for (Integer role : item.portRoles(port.get().id(), port.get().pieceTypeId())) {
                    int typeIndex = piece.get().typeIds().indexOf(role);
                    if (typeIndex < 0) {
                        issues.add(SceneIssue.error("E_PORT_TYPE_MISMATCH", "Port \"" + port.get().name()
                                + "\" plays piece type " + role + ", which \"" + piece.get().name() + "\" does not have.", item.id(), null));
                    } else if (!Objects.equals(piece.get().typeKinds().get(typeIndex), port.get().kind())) {
                        issues.add(SceneIssue.error("E_PORT_KIND_MISMATCH", "Port \"" + port.get().name() + "\" ("
                                + port.get().kind() + ") cannot play type \"" + piece.get().typeNames().get(typeIndex) + "\" ("
                                + piece.get().typeKinds().get(typeIndex) + ").", item.id(), null));
                    }
                }
            }
            validateRoleCardinality(item, piece.get(), context, issues);
        }

        Map<String, List<Clip>> clipsByTrack = new HashMap<>();
        for (Clip clip : doc.clips()) {
            clipsByTrack.computeIfAbsent(clip.trackId(), k -> new ArrayList<>()).add(clip);
            Track track = tracks.get(clip.trackId());
            if (track == null) continue; // structural error already reported
            Item item = items.get(track.itemId());
            PieceRef piece = item == null ? null : pieces.get(item.id());
            if (item == null || piece == null) continue;
            validateClip(clip, item, piece, context, catalog, issues);
        }
        validateOverlaps(clipsByTrack, context, issues);
        issues.sort((a, b) -> Boolean.compare(b.isError(), a.isError()));
        return issues;
    }

    private static void validateStructure(SceneDocument doc, List<SceneIssue> issues) {
        if (doc.durationMs() <= 0) {
            issues.add(SceneIssue.error("E_DURATION", "durationMs must be positive.", null, null));
        }
        if (doc.board() == null || doc.board().width() <= 0 || doc.board().height() <= 0) {
            issues.add(SceneIssue.error("E_BOARD", "The board needs a positive width and height.", null, null));
        }
        if (doc.schedule() != null) {
            for (String problem : ScheduleRules.problems(doc.schedule())) {
                issues.add(SceneIssue.error("E_SCHEDULE", "Button schedule: " + problem + ".", null, null));
            }
        }
        Set<String> layerIds = uniqueIds(doc.layers().stream().map(SceneDocument.Layer::id).toList(), "layer", issues);
        Set<String> itemIds = uniqueIds(doc.items().stream().map(Item::id).toList(), "item", issues);
        Set<String> trackIds = uniqueIds(doc.tracks().stream().map(Track::id).toList(), "track", issues);
        uniqueIds(doc.clips().stream().map(Clip::id).toList(), "clip", issues);
        for (Item item : doc.items()) {
            if (item.layerId() != null && !layerIds.contains(item.layerId())) {
                issues.add(SceneIssue.error("E_REF", "Item " + item.id() + " references unknown layer " + item.layerId() + ".",
                        item.id(), null));
            }
            if (item.width() <= 0 || item.height() <= 0) {
                issues.add(SceneIssue.error("E_ITEM_SIZE", "Item " + item.id() + " needs a positive size.", item.id(), null));
            }
        }
        for (Track track : doc.tracks()) {
            if (!itemIds.contains(track.itemId())) {
                issues.add(SceneIssue.error("E_REF", "Track " + track.id() + " references unknown item " + track.itemId() + ".",
                        null, null));
            }
        }
        for (Clip clip : doc.clips()) {
            if (!trackIds.contains(clip.trackId())) {
                issues.add(SceneIssue.error("E_REF", "Clip " + clip.id() + " references unknown track " + clip.trackId() + ".",
                        null, clip.id()));
            }
            if (clip.startMs() < 0 || clip.durationMs() <= 0) {
                issues.add(SceneIssue.error("E_CLIP_TIME", "Clip " + clip.id() + " needs startMs ≥ 0 and durationMs > 0.",
                        null, clip.id()));
            }
        }
    }

    private static Set<String> uniqueIds(List<String> ids, String what, List<SceneIssue> issues) {
        Set<String> seen = new HashSet<>();
        for (String id : ids) {
            if (id == null || id.isBlank()) {
                issues.add(SceneIssue.error("E_ID", "A " + what + " has no id.", null, null));
            } else if (!seen.add(id)) {
                issues.add(SceneIssue.error("E_ID", "Duplicate " + what + " id " + id + ".", null, null));
            }
        }
        return seen;
    }

    private static void validateClip(Clip clip, Item item, PieceRef piece, SceneContext context, OutputCatalog catalog,
                                     List<SceneIssue> issues) {
        String where = " (clip " + clip.id() + " on \"" + piece.name() + "\" at " + clip.startMs() + " ms)";
        if (clip.pieceTypeActionId() == null) {
            issues.add(SceneIssue.warning("W_CLIP_NO_ACTION", "The clip has no action and will be ignored" + where + ".",
                    item.id(), clip.id()));
            return;
        }
        Optional<ActionRef> actionOpt = context.actionById(clip.pieceTypeActionId());
        if (actionOpt.isEmpty()) {
            issues.add(SceneIssue.error("E_ACTION_UNKNOWN", "Action " + clip.pieceTypeActionId() + " no longer exists" + where + ".",
                    item.id(), clip.id()));
            return;
        }
        ActionRef action = actionOpt.get();
        if (!piece.typeIds().contains(action.typeId())) {
            issues.add(SceneIssue.error("E_ACTION_NOT_OF_PIECE", "Action \"" + action.name() + "\" belongs to type \""
                    + action.typeName() + "\", which the piece does not have" + where + ".", item.id(), clip.id()));
            return;
        }
        if (action.kind() != null && !reachesPort(item, action.typeId(), context)) {
            issues.add(SceneIssue.warning("W_NO_PORT", item.ports().isEmpty()
                    ? "The item is not bound to any port, so this action cannot reach hardware" + where + "."
                    : "No port of the item plays type \"" + action.typeName() + "\", so this action cannot reach hardware"
                    + where + " (bind one as that type).", item.id(), clip.id()));
        }

        Map<String, ActionParameterDto> declared = new HashMap<>();
        for (ActionParameterDto p : action.parameters()) {
            declared.put(p.getName(), p);
        }
        CatalogAction catalogAction = catalogAction(catalog, action);
        String animation = animationOf(action, clip);
        Map<String, CatalogParam> catalogParams = catalogAction == null ? Map.of()
                : (animation == null ? catalogAction.allParams() : catalogAction.paramsFor(animation));

        for (ActionParameterDto p : action.parameters()) {
            Object value = clip.paramValues().get(p.getName());
            boolean dynamic = Boolean.TRUE.equals(p.getDynamic());
            boolean isDuration = Boolean.TRUE.equals(p.getIsDuration());
            if (isDuration || !dynamic) {
                if (value != null) {
                    issues.add(SceneIssue.warning("W_PARAM_IGNORED", "Parameter \"" + p.getName() + "\" is "
                            + (isDuration ? "the clip duration" : "fixed on the action") + " and its clip value is ignored"
                            + where + ".", item.id(), clip.id()));
                }
                continue;
            }
            if (value == null || (value instanceof String s && s.isBlank())) {
                if (Boolean.TRUE.equals(p.getRequired())) {
                    issues.add(SceneIssue.error("E_PARAM_REQUIRED", "Parameter \"" + p.getName() + "\" is required"
                            + where + ".", item.id(), clip.id()));
                }
                continue;
            }
            String typeError = checkAttributeType(p, value);
            if (typeError != null) {
                issues.add(SceneIssue.error("E_PARAM_TYPE", "Parameter \"" + p.getName() + "\" " + typeError + where + ".",
                        item.id(), clip.id()));
                continue;
            }
            CatalogParam cp = catalogParams.get(p.getName());
            if (cp != null) {
                String catalogError = checkCatalogValue(cp, value);
                if (catalogError != null) {
                    issues.add(SceneIssue.error("E_PARAM_RANGE", "Parameter \"" + p.getName() + "\" " + catalogError + where + ".",
                            item.id(), clip.id()));
                }
            } else if (catalogAction != null && animation != null && catalogAction.allParams().containsKey(p.getName())) {
                issues.add(SceneIssue.warning("W_ANIMATION_PARAM", "Parameter \"" + p.getName() + "\" does not apply to animation \""
                        + animation + "\" and will not be exported" + where + ".", item.id(), clip.id()));
            }
        }
        for (String name : clip.paramValues().keySet()) {
            if (!declared.containsKey(name)) {
                issues.add(SceneIssue.warning("W_PARAM_UNKNOWN", "Parameter \"" + name + "\" is not a parameter of action \""
                        + action.name() + "\"" + where + ".", item.id(), clip.id()));
            }
        }
        if (catalogAction != null && "fade".equals(animation) && !clip.paramValues().containsKey("from_color")
                && declared.containsKey("from_color")) {
            issues.add(SceneIssue.warning("W_FADE_FROM_BLACK", "The fade has no from_color: it will start from black" + where + ".",
                    item.id(), clip.id()));
        }
    }

    /**
     * A type of the piece drives one port: several ports playing the same type (scenes saved before
     * the editor enforced it) and one port playing several types of the piece (chosen on purpose: the
     * tracks of both types drive it) are warned about, not refused.
     */
    private static void validateRoleCardinality(Item item, PieceRef piece, SceneContext context, List<SceneIssue> issues) {
        Map<Integer, List<String>> portsByType = new LinkedHashMap<>();
        Map<String, List<String>> typesByPort = new LinkedHashMap<>();
        for (Integer portId : item.ports()) {
            Optional<PortRef> port = portId == null ? Optional.empty() : context.portById(portId);
            if (port.isEmpty()) continue;
            for (Integer role : item.portRoles(port.get().id(), port.get().pieceTypeId())) {
                int typeIndex = piece.typeIds().indexOf(role);
                if (typeIndex < 0) continue;
                portsByType.computeIfAbsent(role, k -> new ArrayList<>()).add(port.get().name());
                typesByPort.computeIfAbsent(port.get().name(), k -> new ArrayList<>()).add(piece.typeNames().get(typeIndex));
            }
        }
        portsByType.forEach((typeId, ports) -> {
            if (ports.size() > 1) {
                issues.add(SceneIssue.warning("W_PORT_ROLE_DUPLICATE", "Ports " + quoted(ports) + " all play type \""
                        + piece.typeNames().get(piece.typeIds().indexOf(typeId)) + "\" of \"" + piece.name()
                        + "\"; a type drives one port.", item.id(), null));
            }
        });
        typesByPort.forEach((port, types) -> {
            if (types.size() > 1) {
                issues.add(SceneIssue.warning("W_PORT_SHARED_ROLES", "Port \"" + port + "\" plays types " + quoted(types)
                        + " of \"" + piece.name() + "\": the tracks of all of them drive it.", item.id(), null));
            }
        });
    }

    private static String quoted(List<String> names) {
        return String.join(", ", names.stream().map(n -> "\"" + n + "\"").toList());
    }

    /** @return whether some bound port of the item plays the given piece type (stale ports play none) */
    private static boolean reachesPort(Item item, int typeId, SceneContext context) {
        for (Integer portId : item.ports()) {
            Optional<PortRef> port = portId == null ? Optional.empty() : context.portById(portId);
            if (port.isPresent() && item.portRoles(port.get().id(), port.get().pieceTypeId()).contains(typeId)) {
                return true;
            }
        }
        return false;
    }

    private static CatalogAction catalogAction(OutputCatalog catalog, ActionRef action) {
        if (catalog == null || action.kind() == null) return null;
        Optional<OutputKind> kind = OutputKind.parse(action.kind());
        if (kind.isEmpty()) return null;
        try {
            return catalog.action(kind.get(), action.name()).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** @return the animation of an {@code animate} clip, from the clip or the static parameter */
    static String animationOf(ActionRef action, Clip clip) {
        Object dynamic = clip.paramValues().get("animation_type");
        if (dynamic != null) return String.valueOf(dynamic);
        for (ActionParameterDto p : action.parameters()) {
            if ("animation_type".equals(p.getName()) && !Boolean.TRUE.equals(p.getDynamic())) {
                return p.getStaticValue();
            }
        }
        return null;
    }

    /** @return an error phrase when {@code value} does not fit the attribute type, else {@code null} */
    static String checkAttributeType(ActionParameterDto p, Object value) {
        AttributeType type = p.getType();
        if (type == null) return null;
        switch (type) {
            case INTEGER -> {
                if (!(value instanceof Number n && n.doubleValue() == Math.floor(n.doubleValue()))
                        && !(value instanceof String s && s.trim().matches("-?\\d+"))) {
                    return "must be an integer";
                }
            }
            case DECIMAL, PRICE -> {
                if (!(value instanceof Number) && !(value instanceof String s && isNumeric(s))) {
                    return "must be a number";
                }
            }
            case BOOLEAN -> {
                if (!(value instanceof Boolean) && !(value instanceof String s
                        && (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false")))) {
                    return "must be true or false";
                }
            }
            case SELECT -> {
                List<String> options = p.getValidators() == null ? null : p.getValidators().getOptions();
                if (options != null && !options.isEmpty() && !options.contains(String.valueOf(value))) {
                    return "must be one of " + options;
                }
            }
            default -> {
                // TEXT and friends accept anything printable
            }
        }
        BigDecimal min = p.getValidators() == null ? null : p.getValidators().getMin();
        BigDecimal max = p.getValidators() == null ? null : p.getValidators().getMax();
        BigDecimal number = asNumber(value);
        if (number != null) {
            if (min != null && number.compareTo(min) < 0) return "must be at least " + min;
            if (max != null && number.compareTo(max) > 0) return "must be at most " + max;
        }
        return null;
    }

    /** @return an error phrase when {@code value} does not satisfy the catalog parameter, else {@code null} */
    static String checkCatalogValue(CatalogParam cp, Object value) {
        switch (cp.type()) {
            case INT -> {
                BigDecimal n = asNumber(value);
                if (n == null || n.stripTrailingZeros().scale() > 0) return "must be an integer";
                return range(cp, n);
            }
            case NUMBER -> {
                BigDecimal n = asNumber(value);
                if (n == null) return "must be a number";
                return range(cp, n);
            }
            case BOOL -> {
                if (!(value instanceof Boolean) && !(value instanceof String s
                        && (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false")))) {
                    return "must be true or false";
                }
                return null;
            }
            case ENUM -> {
                if (cp.values() != null && !cp.values().contains(String.valueOf(value))) {
                    return "must be one of " + cp.values();
                }
                return null;
            }
            case RGB -> {
                return isRgb(value) ? null : "must be an RGB colour: [r, g, b], \"r,g,b\" or \"#rrggbb\"";
            }
            case RGB_LIST -> {
                if (value instanceof List<?> list) {
                    for (Object v : list) {
                        if (!isRgb(v)) return "must be a list of RGB colours";
                    }
                    return null;
                }
                return value instanceof String ? null : "must be a list of RGB colours";
            }
            default -> {
                return null;
            }
        }
    }

    private static String range(CatalogParam cp, BigDecimal n) {
        if (cp.min() != null && n.compareTo(cp.min()) < 0) return "must be at least " + cp.min();
        if (cp.max() != null && n.compareTo(cp.max()) > 0) return "must be at most " + cp.max();
        return null;
    }

    static boolean isRgb(Object value) {
        if (value instanceof List<?> list) {
            if (list.size() != 3) return false;
            for (Object v : list) {
                BigDecimal n = asNumber(v);
                if (n == null || n.intValue() < 0 || n.intValue() > 255) return false;
            }
            return true;
        }
        if (value instanceof String s) {
            String t = s.trim();
            if (t.matches("#[0-9a-fA-F]{6}") || t.matches("#[0-9a-fA-F]{3}")) return true;
            if (t.startsWith("[")) t = t.substring(1, t.endsWith("]") ? t.length() - 1 : t.length());
            String[] parts = t.split(",");
            if (parts.length != 3) return false;
            for (String part : parts) {
                if (!part.trim().matches("\\d{1,3}") || Integer.parseInt(part.trim()) > 255) return false;
            }
            return true;
        }
        return false;
    }

    static BigDecimal asNumber(Object value) {
        if (value instanceof Number n) return new BigDecimal(n.toString());
        if (value instanceof String s && isNumeric(s)) return new BigDecimal(s.trim());
        return null;
    }

    static boolean isNumeric(String s) {
        return s != null && s.trim().matches("-?\\d+(\\.\\d+)?");
    }

    private static void validateOverlaps(Map<String, List<Clip>> clipsByTrack, SceneContext context, List<SceneIssue> issues) {
        for (List<Clip> clips : clipsByTrack.values()) {
            List<Clip> sorted = new ArrayList<>(clips);
            sorted.sort((a, b) -> Long.compare(a.startMs(), b.startMs()));
            for (int i = 1; i < sorted.size(); i++) {
                Clip previous = sorted.get(i - 1);
                Clip current = sorted.get(i);
                if (current.startMs() < previous.endMs()) {
                    String name = previous.pieceTypeActionId() == null ? "?" : context.actionById(previous.pieceTypeActionId())
                            .map(ActionRef::name).orElse("?").toLowerCase(Locale.ROOT);
                    issues.add(SceneIssue.warning("W_OVERLAP", "Clip " + current.id() + " starts before clip " + previous.id()
                            + " (" + name + ") ends on the same track.", null, current.id()));
                }
            }
        }
    }
}
