package com.stocka.backend.modules.contract;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * In-memory copy of {@code contract/catalog.json}: the output kinds, their config schemas, the
 * actions each controller exposes and the board pin rules.
 *
 * <p>The file is a byte-identical copy of {@code rpi4-system/contract/catalog.json}, which is the
 * source of truth. It is read once at startup; a missing or malformed resource fails fast because
 * every port and action validation depends on it.
 */
@Component
public class OutputCatalog {
    /** Default board every organization targets until devices carry their own board id. */
    public static final String DEFAULT_BOARD = "rpi4b";

    private static final String RESOURCE = "contract/catalog.json";

    private final JsonNode raw;
    private final int version;
    private final Map<OutputKind, CatalogKind> kinds = new EnumMap<>(OutputKind.class);
    private final Map<String, BoardSpec> boards = new LinkedHashMap<>();

    public OutputCatalog() {
        this(new ClassPathResource(RESOURCE));
    }

    /**
     * Builds the catalog from an arbitrary resource; used by tests that exercise malformed files.
     *
     * @param resource classpath (or file) resource holding the catalog JSON
     */
    public OutputCatalog(org.springframework.core.io.Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            this.raw = JsonMapper.builder().build().readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException("No se ha podido leer " + RESOURCE, e);
        }
        if (!"stocka-catalog".equals(raw.path("format").asText())) {
            throw new IllegalStateException(RESOURCE + " no es un catálogo stocka-catalog");
        }
        this.version = raw.path("version").asInt();
        parseKinds(raw.path("kinds"));
        parseBoards(raw.path("boards"));
        for (OutputKind kind : OutputKind.values()) {
            if (!kinds.containsKey(kind)) {
                throw new IllegalStateException(RESOURCE + " no define el kind " + kind.catalogId());
            }
        }
    }

    /** @return the catalog as parsed, for the {@code GET /contract/catalog} endpoint */
    public JsonNode raw() {
        return raw;
    }

    /** @return the catalog version number */
    public int version() {
        return version;
    }

    /**
     * Spec of a kind.
     *
     * @param kind output kind
     * @return its catalog entry (never {@code null})
     */
    public CatalogKind kind(OutputKind kind) {
        return kinds.get(kind);
    }

    /**
     * Whether ports of {@code kind} need no piece type.
     *
     * @param kind output kind
     * @return {@code true} for infrastructure kinds
     */
    public boolean isInfrastructure(OutputKind kind) {
        return kinds.get(kind).infrastructure();
    }

    /**
     * Whether ports of {@code kind} carry a single GPIO pin in {@code Port.pin}.
     *
     * @param kind output kind
     * @return {@code true} when the kind has a pin
     */
    public boolean hasPin(OutputKind kind) {
        return kinds.get(kind).pin();
    }

    /**
     * Config schema of a kind.
     *
     * @param kind output kind
     * @return ordered map of config keys
     */
    public Map<String, CatalogParam> configSchema(OutputKind kind) {
        return kinds.get(kind).config();
    }

    /**
     * Looks up an action of a kind.
     *
     * @param kind   output kind
     * @param action controller method name
     * @return the action, or empty when the kind has no such action
     */
    public Optional<CatalogAction> action(OutputKind kind, String action) {
        if (action == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(kinds.get(kind).actions().get(action));
    }

    /**
     * Board pin rules.
     *
     * @param boardId board id, e.g. {@link #DEFAULT_BOARD}
     * @return the board spec, or empty when the catalog does not describe it
     */
    public Optional<BoardSpec> board(String boardId) {
        return Optional.ofNullable(boards.get(boardId));
    }

    // ------------------------------------------------------------------ parsing

    private void parseKinds(JsonNode node) {
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            OutputKind kind = OutputKind.parse(entry.getKey())
                    .orElseThrow(() -> new IllegalStateException(RESOURCE + " contiene un kind desconocido: " + entry.getKey()));
            JsonNode spec = entry.getValue();
            Map<String, CatalogParam> config = parseParams(spec.path("config"));
            Map<String, CatalogAction> actions = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> a : spec.path("actions").properties()) {
                actions.put(a.getKey(), parseAction(a.getKey(), a.getValue()));
            }
            kinds.put(kind, new CatalogKind(
                    kind,
                    spec.path("controller").asText(),
                    spec.path("infrastructure").asBoolean(false),
                    spec.path("pin").asBoolean(false),
                    Collections.unmodifiableMap(config),
                    Collections.unmodifiableMap(actions)));
        }
    }

    private static CatalogAction parseAction(String name, JsonNode node) {
        Map<String, CatalogAnimation> animations = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> a : node.path("animations").properties()) {
            animations.put(a.getKey(), new CatalogAnimation(
                    a.getKey(),
                    Collections.unmodifiableMap(parseParams(a.getValue().path("params"))),
                    parseStrings(a.getValue().path("unsupported"))));
        }
        return new CatalogAction(
                name,
                Collections.unmodifiableMap(parseParams(node.path("params"))),
                Collections.unmodifiableMap(animations),
                parseStrings(node.path("unsupported")),
                node.path("continuous").asBoolean(false));
    }

    private static Map<String, CatalogParam> parseParams(JsonNode node) {
        Map<String, CatalogParam> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> p : node.properties()) {
            JsonNode v = p.getValue();
            out.put(p.getKey(), new CatalogParam(
                    p.getKey(),
                    CatalogType.fromCatalogId(v.path("type").asText("string")),
                    v.path("required").asBoolean(false),
                    decimalOrNull(v.path("min")),
                    decimalOrNull(v.path("max")),
                    parseStrings(v.path("values")),
                    v.path("duration").asBoolean(false),
                    textOrNull(v.path("asset")),
                    textOrNull(v.path("ref")),
                    v.has("default") ? v.path("default").toString() : null));
        }
        return out;
    }

    private void parseBoards(JsonNode node) {
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            JsonNode b = entry.getValue();
            Map<Integer, String> reserved = new LinkedHashMap<>();
            b.path("reserved").properties().forEach(e -> reserved.put(Integer.parseInt(e.getKey()), e.getValue().asText()));
            Map<Integer, String> warnings = new LinkedHashMap<>();
            b.path("warnings").properties().forEach(e -> warnings.put(Integer.parseInt(e.getKey()), e.getValue().asText()));
            Map<Integer, Integer> ws = new LinkedHashMap<>();
            Map<Integer, String> wsWarnings = new LinkedHashMap<>();
            b.path("ws281x").properties().forEach(e -> {
                ws.put(Integer.parseInt(e.getKey()), e.getValue().path("channel").asInt());
                if (e.getValue().hasNonNull("warning")) {
                    wsWarnings.put(Integer.parseInt(e.getKey()), e.getValue().path("warning").asText());
                }
            });
            Set<Integer> avoid = new LinkedHashSet<>();
            for (JsonNode n : b.path("dma").path("avoid")) {
                avoid.add(n.asInt());
            }
            boards.put(entry.getKey(), new BoardSpec(
                    entry.getKey(),
                    b.path("gpios").path("min").asInt(0),
                    b.path("gpios").path("max").asInt(27),
                    Collections.unmodifiableMap(reserved),
                    Collections.unmodifiableMap(warnings),
                    Collections.unmodifiableMap(ws),
                    Collections.unmodifiableMap(wsWarnings),
                    b.path("dma").path("min").asInt(0),
                    b.path("dma").path("max").asInt(14),
                    Collections.unmodifiableSet(avoid)));
        }
    }

    private static List<String> parseStrings(JsonNode node) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : node) {
            out.add(n.asText());
        }
        return Collections.unmodifiableList(out);
    }

    private static BigDecimal decimalOrNull(JsonNode node) {
        return node.isNumber() ? node.decimalValue() : null;
    }

    private static String textOrNull(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }
}
