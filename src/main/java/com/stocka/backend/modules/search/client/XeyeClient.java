package com.stocka.backend.modules.search.client;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.stocka.backend.modules.search.SemanticSearchProperties;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Thin HTTP client for the XEYE public API ({@code https://api.xeye.es/v1}).
 *
 * <p>Only the calls Stocka needs: lists, elements, search and training launches. Field names
 * follow the XEYE contract (snake_case); every failure surfaces as a {@link XeyeException}
 * carrying the status and the error code of the envelope. The API key travels in the
 * {@code X-API-Key} header and is never logged.
 */
@Component
public class XeyeClient {

    /** Elements per batch request; well under the 10 000 elements / 16 MB cap of XEYE. */
    static final int BATCH_SIZE = 500;
    /** Ids per batch-delete request (XEYE cap). */
    static final int DELETE_BATCH_SIZE = 10_000;

    /** A list of the XEYE account. */
    public record XeyeList(long id, String name) {}

    /** What is sent for one element: the indexed text, optional context and the opaque params. */
    public record ElementInput(String text, String description, Map<String, Object> params) {}

    /** One search hit: the element text, its 0..1 score and its params as returned by XEYE. */
    public record Hit(String item, double score, JsonNode params) {}

    /** A training launched for a list. */
    public record Training(long id, String status) {}

    private final RestClient http;
    private final SemanticSearchProperties properties;
    private final ObjectMapper json = JsonMapper.builder().build();

    @Autowired
    public XeyeClient(SemanticSearchProperties properties) {
        this(properties, RestClient.builder().requestFactory(requestFactory(properties)));
    }

    XeyeClient(SemanticSearchProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        this.http = builder
                .baseUrl(properties.getBaseUrl())
                .defaultHeader("X-API-Key", properties.getApiKey())
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    private static JdkClientHttpRequestFactory requestFactory(SemanticSearchProperties properties) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(properties.getTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(properties.getTimeout());
        return factory;
    }

    // ---- lists ---------------------------------------------------------------------------

    /**
     * Looks a list up by its exact name.
     *
     * @param name list name
     * @return the list when the account has one with that name
     * @throws XeyeException when the call fails
     */
    public Optional<XeyeList> findList(String name) {
        JsonNode page = exchange(http.get().uri(b -> b.path("/lists")
                .queryParam("q", name).queryParam("limit", 200).build()));
        for (JsonNode item : page.path("items")) {
            if (name.equals(item.path("name").asText())) {
                return Optional.of(toList(item));
            }
        }
        return Optional.empty();
    }

    /**
     * Creates a public list (the API only searches public lists of the account).
     *
     * @param name        list name
     * @param description human description shown in the XEYE console
     * @return the created list
     * @throws XeyeException when the call fails
     */
    public XeyeList createList(String name, String description) {
        ObjectNode body = json.createObjectNode();
        body.put("name", name);
        body.put("description", description);
        body.put("is_public", true);
        body.put("llm_enrichment", properties.isLlmEnrichment());
        return toList(exchange(http.post().uri("/lists").contentType(MediaType.APPLICATION_JSON).body(body.toString())));
    }

    // ---- elements ------------------------------------------------------------------------

    /**
     * Adds one element to a list.
     *
     * @param listId  XEYE list id
     * @param element text, description and params
     * @return id of the created element
     * @throws XeyeException when the call fails
     */
    public long createElement(long listId, ElementInput element) {
        JsonNode created = exchange(http.post().uri("/lists/{id}/elements", listId)
                .contentType(MediaType.APPLICATION_JSON).body(elementBody(element).toString()));
        return created.path("id").asLong();
    }

    /**
     * Adds many elements in batches, keeping the input order.
     *
     * @param listId   XEYE list id
     * @param elements elements to add
     * @return ids of the created elements, one per input in the same order
     * @throws XeyeException when any batch fails (earlier batches stay created)
     */
    public List<Long> createElements(long listId, List<ElementInput> elements) {
        List<Long> ids = new ArrayList<>(elements.size());
        for (int from = 0; from < elements.size(); from += BATCH_SIZE) {
            List<ElementInput> chunk = elements.subList(from, Math.min(from + BATCH_SIZE, elements.size()));
            ObjectNode body = json.createObjectNode();
            ArrayNode array = body.putArray("elements");
            chunk.forEach(e -> array.add(elementBody(e)));
            JsonNode created = exchange(http.post().uri("/lists/{id}/elements/batch", listId)
                    .contentType(MediaType.APPLICATION_JSON).body(body.toString()));
            for (JsonNode item : created.path("items")) {
                ids.add(item.path("id").asLong());
            }
        }
        return ids;
    }

    /**
     * Replaces the text, description and params of an element.
     *
     * @param listId    XEYE list id
     * @param elementId XEYE element id
     * @param element   new content
     * @throws XeyeException when the call fails ({@link XeyeException#isNotFound()} when the
     *                       element no longer exists)
     */
    public void updateElement(long listId, long elementId, ElementInput element) {
        exchange(http.patch().uri("/lists/{id}/elements/{elementId}", listId, elementId)
                .contentType(MediaType.APPLICATION_JSON).body(elementBody(element).toString()));
    }

    /**
     * Deletes one element; an element that is already gone is not an error.
     *
     * @param listId    XEYE list id
     * @param elementId XEYE element id
     * @throws XeyeException when the call fails for another reason
     */
    public void deleteElement(long listId, long elementId) {
        try {
            exchange(http.delete().uri("/lists/{id}/elements/{elementId}", listId, elementId));
        } catch (XeyeException ex) {
            if (!ex.isNotFound()) throw ex;
        }
    }

    /**
     * Deletes many elements; unknown ids are ignored by XEYE.
     *
     * @param listId XEYE list id
     * @param ids    element ids
     * @throws XeyeException when any batch fails
     */
    public void deleteElements(long listId, List<Long> ids) {
        for (int from = 0; from < ids.size(); from += DELETE_BATCH_SIZE) {
            List<Long> chunk = ids.subList(from, Math.min(from + DELETE_BATCH_SIZE, ids.size()));
            ObjectNode body = json.createObjectNode();
            ArrayNode array = body.putArray("ids");
            chunk.forEach(array::add);
            exchange(http.post().uri("/lists/{id}/elements/batch-delete", listId)
                    .contentType(MediaType.APPLICATION_JSON).body(body.toString()));
        }
    }

    // ---- search and trainings ------------------------------------------------------------

    /**
     * Hybrid (text + semantic) search over a public list.
     *
     * @param listName name of the list
     * @param term     what the user typed (1..500 characters)
     * @param limit    maximum hits (1..1000)
     * @return the hits ordered by descending score
     * @throws XeyeException when the call fails
     */
    public List<Hit> search(String listName, String term, int limit) {
        ObjectNode body = json.createObjectNode();
        body.put("list_name", listName);
        body.put("search_term", term);
        body.put("limit", limit);
        JsonNode response = exchange(http.post().uri("/search")
                .contentType(MediaType.APPLICATION_JSON).body(body.toString()));
        List<Hit> hits = new ArrayList<>();
        for (JsonNode result : response.path("results")) {
            hits.add(new Hit(result.path("item").asText(), result.path("score").asDouble(), result.get("params")));
        }
        return hits;
    }

    /**
     * Launches a training of the list with the configured model and enrichment choice.
     *
     * @param listId XEYE list id
     * @return the launched training
     * @throws XeyeException when the call fails ({@link XeyeException#isConflict()} when a
     *                       training is already queued or running)
     */
    public Training launchTraining(long listId) {
        ObjectNode body = json.createObjectNode();
        if (!properties.getEmbeddingModel().isBlank()) {
            body.put("embedding_model", properties.getEmbeddingModel());
        }
        body.put("no_descriptions", !properties.isLlmEnrichment());
        JsonNode training = exchange(http.post().uri("/lists/{id}/trainings", listId)
                .contentType(MediaType.APPLICATION_JSON).body(body.toString()));
        return new Training(training.path("id").asLong(), training.path("status").asText());
    }

    // ---- plumbing ------------------------------------------------------------------------

    private ObjectNode elementBody(ElementInput element) {
        ObjectNode body = json.createObjectNode();
        body.put("text", element.text());
        body.put("description", element.description());
        body.set("params", json.valueToTree(element.params()));
        return body;
    }

    private static XeyeList toList(JsonNode node) {
        return new XeyeList(node.path("id").asLong(), node.path("name").asText());
    }

    private JsonNode exchange(RestClient.RequestHeadersSpec<?> request) {
        try {
            String body = request.retrieve().body(String.class);
            return body == null || body.isBlank() ? json.createObjectNode() : json.readTree(body);
        } catch (RestClientResponseException ex) {
            throw toException(ex);
        } catch (ResourceAccessException ex) {
            throw new XeyeException(0, null, "XEYE unreachable: " + ex.getMessage(), ex);
        }
    }

    private XeyeException toException(RestClientResponseException ex) {
        String code = null;
        String message = ex.getStatusText();
        try {
            JsonNode envelope = json.readTree(ex.getResponseBodyAsString());
            code = envelope.path("code").asText(null);
            message = envelope.path("message").asText(message);
        } catch (RuntimeException ignored) {
            // not a JSON envelope: keep the status text
        }
        return new XeyeException(ex.getStatusCode().value(), code, "XEYE " + ex.getStatusCode().value()
                + (code == null ? "" : " " + code) + ": " + message, ex);
    }
}
