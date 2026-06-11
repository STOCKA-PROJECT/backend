package com.stocka.backend.modules.timelines.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.stocka.backend.modules.common.error.ApiException;
import com.stocka.backend.modules.common.error.ErrorCodes;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Round-trips the timeline scene document between its JSON tree ({@link JsonNode}) and the stored
 * TEXT column. Keeps the Jackson 3 ({@code tools.jackson.*}) choice in one place, mirroring
 * {@code ActionParametersJsonCodec}.
 */
@Component
public class TimelineSceneJsonCodec {
    private final ObjectMapper mapper = JsonMapper.builder().build();

    /**
     * Serializes a document tree to its stored JSON string.
     *
     * @param document the document tree; may be {@code null}
     * @return the JSON string, or {@code null} when {@code document} is {@code null}
     */
    public String serialize(JsonNode document) {
        if (document == null || document.isNull()) {
            return null;
        }
        try {
            return mapper.writeValueAsString(document);
        } catch (JacksonException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.TIMELINE_SCENE_DOCUMENT_INVALID, null, e);
        }
    }

    /**
     * Parses a stored document string back into a JSON tree.
     *
     * @param json the stored document; may be {@code null} or blank
     * @return the parsed tree, or {@code null} when nothing is stored
     */
    public JsonNode deserialize(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(json);
        } catch (JacksonException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Documento de escena corrupto");
        }
    }
}
