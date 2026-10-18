package com.stocka.backend.modules.timelines.scene;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.stocka.backend.modules.common.error.ApiException;
import com.stocka.backend.modules.common.error.ErrorCodes;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Converts between the stored JSON tree of a scene and {@link SceneDocument}.
 *
 * <p>Parsing ignores fields the server does not model. Serializing merges them back: every key of
 * the original tree that the typed model does not produce is copied to the result, at the document
 * level and for each layer, item, track and clip matched by {@code id}. So an edit made through the
 * server never drops data the editor stored.
 */
@Component
public class SceneJsonCodec {

    private final JsonMapper mapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    /**
     * @param tree stored document; {@code null} yields an empty scene
     * @return the typed document
     * @throws ApiException 400 when the schema version is newer than the server knows
     */
    public SceneDocument parse(JsonNode tree) {
        if (tree == null || tree.isNull() || tree.isMissingNode()) {
            return SceneDocument.empty(SceneDocument.DEFAULT_DURATION_MS, null);
        }
        int version = tree.path("schemaVersion").asInt(SceneDocument.SCHEMA_VERSION);
        if (version > SceneDocument.SCHEMA_VERSION) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.TIMELINE_SCENE_SCHEMA_UNSUPPORTED,
                    Map.of("version", version, "supported", SceneDocument.SCHEMA_VERSION));
        }
        try {
            return mapper.treeToValue(tree, SceneDocument.class);
        } catch (RuntimeException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.TIMELINE_SCENE_DOCUMENT_INVALID, null, e);
        }
    }

    /**
     * @param document typed document
     * @param original tree it was parsed from, or {@code null}
     * @return the JSON tree to store, with the original's unknown fields preserved
     */
    public JsonNode toJson(SceneDocument document, JsonNode original) {
        ObjectNode result = mapper.valueToTree(document);
        if (original instanceof ObjectNode source) {
            copyUnknown(source, result);
            mergeById(source, result, "layers");
            mergeById(source, result, "items");
            mergeById(source, result, "tracks");
            mergeById(source, result, "clips");
            if (source.get("board") instanceof ObjectNode sb && result.get("board") instanceof ObjectNode rb) {
                copyUnknown(sb, rb);
            }
        }
        return result;
    }

    /** @return the mapper used for scene JSON */
    public JsonMapper mapper() {
        return mapper;
    }

    private static void mergeById(ObjectNode source, ObjectNode result, String field) {
        if (!(source.get(field) instanceof ArrayNode sourceArray) || !(result.get(field) instanceof ArrayNode resultArray)) {
            return;
        }
        Map<String, ObjectNode> byId = new HashMap<>();
        for (JsonNode node : sourceArray) {
            if (node instanceof ObjectNode o && o.get("id") != null) {
                byId.put(o.get("id").asText(), o);
            }
        }
        for (JsonNode node : resultArray) {
            if (node instanceof ObjectNode o && o.get("id") != null) {
                ObjectNode from = byId.get(o.get("id").asText());
                if (from != null) copyUnknown(from, o);
            }
        }
    }

    private static void copyUnknown(ObjectNode from, ObjectNode to) {
        Iterator<Map.Entry<String, JsonNode>> fields = from.properties().iterator();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (!to.has(entry.getKey())) {
                to.set(entry.getKey(), entry.getValue());
            }
        }
    }
}
