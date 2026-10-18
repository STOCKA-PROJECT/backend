package com.stocka.backend.modules.search.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.stocka.backend.modules.search.SemanticSearchProperties;
import com.stocka.backend.modules.search.client.XeyeClient.ElementInput;
import com.stocka.backend.modules.search.client.XeyeClient.Hit;
import com.stocka.backend.modules.search.client.XeyeClient.Training;
import com.stocka.backend.modules.search.client.XeyeClient.XeyeList;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("XeyeClient")
class XeyeClientTest {

    private static final String BASE = "https://xeye.test/v1";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private MockRestServiceServer server;
    private SemanticSearchProperties properties;
    private XeyeClient client;

    @BeforeEach
    void setUp() {
        properties = new SemanticSearchProperties();
        properties.setEnabled(true);
        properties.setBaseUrl(BASE);
        properties.setApiKey("secret-key");
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new XeyeClient(properties, builder);
    }

    private static JsonNode body(MockClientHttpRequest request) {
        return JSON.readTree(request.getBodyAsString());
    }

    private static String error(int status, String code, String message) {
        return "{\"status\":" + status + ",\"error\":\"x\",\"code\":\"" + code + "\",\"message\":\"" + message + "\"}";
    }

    @Nested
    @DisplayName("authentication")
    class Authentication {

        @Test
        @DisplayName("sends the API key and accepts JSON on every call")
        void headers() {
            server.expect(requestTo(BASE + "/search"))
                    .andExpect(header("X-API-Key", "secret-key"))
                    .andExpect(header("Accept", MediaType.APPLICATION_JSON_VALUE))
                    .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

            client.search("l", "t", 5);

            server.verify();
        }
    }

    @Nested
    @DisplayName("lists")
    class Lists {

        @Test
        @DisplayName("findList returns the list whose name matches exactly")
        void findListExact() {
            server.expect(requestTo(BASE + "/lists?q=stocka-org-1&limit=200"))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess("{\"items\":[{\"id\":7,\"name\":\"stocka-org-10\"},"
                            + "{\"id\":8,\"name\":\"stocka-org-1\"}],\"total\":2,\"offset\":0,\"limit\":200}",
                            MediaType.APPLICATION_JSON));

            Optional<XeyeList> list = client.findList("stocka-org-1");

            assertThat(list).contains(new XeyeList(8, "stocka-org-1"));
        }

        @Test
        @DisplayName("findList is empty when only similar names exist")
        void findListMissing() {
            server.expect(requestTo(BASE + "/lists?q=stocka-org-1&limit=200"))
                    .andRespond(withSuccess("{\"items\":[{\"id\":7,\"name\":\"stocka-org-10\"}],\"total\":1}",
                            MediaType.APPLICATION_JSON));

            assertThat(client.findList("stocka-org-1")).isEmpty();
        }

        @Test
        @DisplayName("createList creates a public list with the enrichment setting")
        void createList() {
            properties.setLlmEnrichment(false);
            AtomicReference<JsonNode> sent = new AtomicReference<>();
            server.expect(requestTo(BASE + "/lists"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(header("Content-Type", MediaType.APPLICATION_JSON_VALUE))
                    .andExpect(request -> sent.set(body((MockClientHttpRequest) request)))
                    .andRespond(withStatus(HttpStatus.CREATED)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body("{\"id\":9,\"name\":\"stocka-org-1\",\"is_public\":true}"));

            XeyeList created = client.createList("stocka-org-1", "Stocka · Acme");

            assertThat(created).isEqualTo(new XeyeList(9, "stocka-org-1"));
            assertThat(sent.get().path("name").asText()).isEqualTo("stocka-org-1");
            assertThat(sent.get().path("description").asText()).isEqualTo("Stocka · Acme");
            assertThat(sent.get().path("is_public").asBoolean()).isTrue();
            assertThat(sent.get().path("llm_enrichment").asBoolean()).isFalse();
        }
    }

    @Nested
    @DisplayName("elements")
    class Elements {

        private final ElementInput input = new ElementInput("Pastor", "Tipus: Figura", Map.of("pieceId", 42));

        @Test
        @DisplayName("createElement posts text, description and params and returns the id")
        void createElement() {
            AtomicReference<JsonNode> sent = new AtomicReference<>();
            server.expect(requestTo(BASE + "/lists/9/elements"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(request -> sent.set(body((MockClientHttpRequest) request)))
                    .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                            .body("{\"id\":501,\"list_id\":9,\"text\":\"Pastor\"}"));

            long id = client.createElement(9, input);

            assertThat(id).isEqualTo(501);
            assertThat(sent.get().path("text").asText()).isEqualTo("Pastor");
            assertThat(sent.get().path("description").asText()).isEqualTo("Tipus: Figura");
            assertThat(sent.get().path("params").path("pieceId").asInt()).isEqualTo(42);
        }

        @Test
        @DisplayName("createElement sends a null description when there is none")
        void createElementNullDescription() {
            AtomicReference<JsonNode> sent = new AtomicReference<>();
            server.expect(requestTo(BASE + "/lists/9/elements"))
                    .andExpect(request -> sent.set(body((MockClientHttpRequest) request)))
                    .andRespond(withSuccess("{\"id\":1}", MediaType.APPLICATION_JSON));

            client.createElement(9, new ElementInput("Pastor", null, Map.of("pieceId", 1)));

            assertThat(sent.get().path("description").isNull()).isTrue();
        }

        @Test
        @DisplayName("createElements splits into batches and keeps the ids in input order")
        void createElementsBatches() {
            List<ElementInput> inputs = new ArrayList<>();
            for (int i = 0; i < XeyeClient.BATCH_SIZE + 1; i++) {
                inputs.add(new ElementInput("p" + i, null, Map.of("pieceId", i)));
            }
            StringBuilder firstItems = new StringBuilder();
            for (int i = 0; i < XeyeClient.BATCH_SIZE; i++) {
                if (i > 0) firstItems.append(',');
                firstItems.append("{\"id\":").append(1000 + i).append('}');
            }
            List<Integer> batchSizes = new ArrayList<>();
            server.expect(times(2), requestTo(BASE + "/lists/9/elements/batch"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(request -> batchSizes.add(body((MockClientHttpRequest) request).path("elements").size()))
                    .andRespond(request -> {
                        boolean first = batchSizes.size() == 1;
                        String items = first ? firstItems.toString() : "{\"id\":2000}";
                        return withSuccess("{\"created\":1,\"items\":[" + items + "]}", MediaType.APPLICATION_JSON)
                                .createResponse(request);
                    });

            List<Long> ids = client.createElements(9, inputs);

            assertThat(batchSizes).containsExactly(XeyeClient.BATCH_SIZE, 1);
            assertThat(ids).hasSize(XeyeClient.BATCH_SIZE + 1);
            assertThat(ids.get(0)).isEqualTo(1000L);
            assertThat(ids.get(XeyeClient.BATCH_SIZE)).isEqualTo(2000L);
        }

        @Test
        @DisplayName("createElements does nothing for an empty input")
        void createElementsEmpty() {
            assertThat(client.createElements(9, List.of())).isEmpty();
            server.verify();
        }

        @Test
        @DisplayName("updateElement patches the element of the list")
        void updateElement() {
            AtomicReference<JsonNode> sent = new AtomicReference<>();
            server.expect(requestTo(BASE + "/lists/9/elements/501"))
                    .andExpect(method(HttpMethod.PATCH))
                    .andExpect(request -> sent.set(body((MockClientHttpRequest) request)))
                    .andRespond(withSuccess("{\"id\":501}", MediaType.APPLICATION_JSON));

            client.updateElement(9, 501, input);

            assertThat(sent.get().path("text").asText()).isEqualTo("Pastor");
            assertThat(sent.get().path("params").path("pieceId").asInt()).isEqualTo(42);
        }

        @Test
        @DisplayName("updateElement surfaces a 404 as not found")
        void updateElementMissing() {
            server.expect(requestTo(BASE + "/lists/9/elements/501"))
                    .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                            .body(error(404, "NOT_FOUND", "no such element")));

            assertThatThrownBy(() -> client.updateElement(9, 501, input))
                    .isInstanceOf(XeyeException.class)
                    .satisfies(ex -> assertThat(((XeyeException) ex).isNotFound()).isTrue());
        }

        @Test
        @DisplayName("deleteElement deletes the element")
        void deleteElement() {
            server.expect(requestTo(BASE + "/lists/9/elements/501"))
                    .andExpect(method(HttpMethod.DELETE))
                    .andRespond(withStatus(HttpStatus.NO_CONTENT));

            client.deleteElement(9, 501);

            server.verify();
        }

        @Test
        @DisplayName("deleteElement ignores an element that is already gone")
        void deleteElementMissing() {
            server.expect(requestTo(BASE + "/lists/9/elements/501"))
                    .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                            .body(error(404, "NOT_FOUND", "gone")));

            client.deleteElement(9, 501);

            server.verify();
        }

        @Test
        @DisplayName("deleteElement rethrows other failures")
        void deleteElementFailure() {
            server.expect(requestTo(BASE + "/lists/9/elements/501"))
                    .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                            .body(error(403, "API_KEY_SCOPE", "scope")));

            assertThatThrownBy(() -> client.deleteElement(9, 501))
                    .isInstanceOf(XeyeException.class)
                    .satisfies(ex -> assertThat(((XeyeException) ex).code()).isEqualTo("API_KEY_SCOPE"));
        }

        @Test
        @DisplayName("deleteElements posts the ids to batch-delete")
        void deleteElements() {
            AtomicReference<JsonNode> sent = new AtomicReference<>();
            server.expect(requestTo(BASE + "/lists/9/elements/batch-delete"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(request -> sent.set(body((MockClientHttpRequest) request)))
                    .andRespond(withSuccess("{\"deleted\":2}", MediaType.APPLICATION_JSON));

            client.deleteElements(9, List.of(1L, 2L));

            assertThat(sent.get().path("ids")).hasSize(2);
            assertThat(sent.get().path("ids").get(1).asLong()).isEqualTo(2L);
        }

        @Test
        @DisplayName("deleteElements does nothing for an empty input")
        void deleteElementsEmpty() {
            client.deleteElements(9, List.of());
            server.verify();
        }
    }

    @Nested
    @DisplayName("search")
    class Search {

        @Test
        @DisplayName("posts list_name, search_term and limit and parses the hits in order")
        void search() {
            AtomicReference<JsonNode> sent = new AtomicReference<>();
            server.expect(requestTo(BASE + "/search"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(request -> sent.set(body((MockClientHttpRequest) request)))
                    .andRespond(withSuccess("{\"success\":true,\"results\":["
                            + "{\"item\":\"Pastor\",\"score\":0.91,\"params\":{\"pieceId\":42}},"
                            + "{\"item\":\"Ovella\",\"score\":0.4,\"params\":\"{\\\"pieceId\\\":7}\"},"
                            + "{\"item\":\"Res\",\"score\":0.1,\"params\":null}],"
                            + "\"total_results\":3,\"duration_ms\":12}", MediaType.APPLICATION_JSON));

            List<Hit> hits = client.search("stocka-org-1", "pastor de fusta", 50);

            assertThat(sent.get().path("list_name").asText()).isEqualTo("stocka-org-1");
            assertThat(sent.get().path("search_term").asText()).isEqualTo("pastor de fusta");
            assertThat(sent.get().path("limit").asInt()).isEqualTo(50);
            assertThat(hits).hasSize(3);
            assertThat(hits.get(0).item()).isEqualTo("Pastor");
            assertThat(hits.get(0).score()).isEqualTo(0.91);
            assertThat(hits.get(0).params().path("pieceId").asInt()).isEqualTo(42);
            assertThat(hits.get(1).params().isTextual()).isTrue();
            assertThat(hits.get(2).params().isNull()).isTrue();
        }

        @Test
        @DisplayName("returns no hits for an empty result set")
        void empty() {
            server.expect(requestTo(BASE + "/search"))
                    .andRespond(withSuccess("{\"success\":true,\"results\":[],\"total_results\":0}",
                            MediaType.APPLICATION_JSON));

            assertThat(client.search("l", "t", 1)).isEmpty();
        }
    }

    @Nested
    @DisplayName("trainings")
    class Trainings {

        @Test
        @DisplayName("launchTraining uses the XEYE default model and keeps descriptions by default")
        void defaults() {
            AtomicReference<JsonNode> sent = new AtomicReference<>();
            server.expect(requestTo(BASE + "/lists/9/trainings"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(request -> sent.set(body((MockClientHttpRequest) request)))
                    .andRespond(withSuccess("{\"id\":77,\"list_id\":9,\"status\":\"QUEUED\"}",
                            MediaType.APPLICATION_JSON));

            Training training = client.launchTraining(9);

            assertThat(training).isEqualTo(new Training(77, "QUEUED"));
            assertThat(sent.get().has("embedding_model")).isFalse();
            assertThat(sent.get().path("no_descriptions").asBoolean()).isFalse();
        }

        @Test
        @DisplayName("launchTraining forwards the configured model and disables descriptions when asked")
        void configured() {
            properties.setEmbeddingModel("paraphrase-multilingual-mpnet-base-v2");
            properties.setLlmEnrichment(false);
            AtomicReference<JsonNode> sent = new AtomicReference<>();
            server.expect(requestTo(BASE + "/lists/9/trainings"))
                    .andExpect(request -> sent.set(body((MockClientHttpRequest) request)))
                    .andRespond(withSuccess("{\"id\":78,\"status\":\"QUEUED\"}", MediaType.APPLICATION_JSON));

            client.launchTraining(9);

            assertThat(sent.get().path("embedding_model").asText()).isEqualTo("paraphrase-multilingual-mpnet-base-v2");
            assertThat(sent.get().path("no_descriptions").asBoolean()).isTrue();
        }

        @Test
        @DisplayName("launchTraining reports a running training as a conflict")
        void conflict() {
            server.expect(requestTo(BASE + "/lists/9/trainings"))
                    .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                            .body(error(409, "CONFLICT", "training in progress")));

            assertThatThrownBy(() -> client.launchTraining(9))
                    .isInstanceOf(XeyeException.class)
                    .satisfies(ex -> {
                        XeyeException xe = (XeyeException) ex;
                        assertThat(xe.isConflict()).isTrue();
                        assertThat(xe.code()).isEqualTo("CONFLICT");
                        assertThat(xe.status()).isEqualTo(409);
                    });
        }
    }

    @Nested
    @DisplayName("errors")
    class Errors {

        @Test
        @DisplayName("map the XEYE envelope to status, code and message")
        void envelope() {
            server.expect(requestTo(BASE + "/search"))
                    .andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
                            .body(error(401, "API_KEY_INVALID", "unknown key")));

            assertThatThrownBy(() -> client.search("l", "t", 1))
                    .isInstanceOf(XeyeException.class)
                    .hasMessageContaining("401")
                    .hasMessageContaining("API_KEY_INVALID")
                    .hasMessageContaining("unknown key")
                    .satisfies(ex -> assertThat(((XeyeException) ex).isRetryable()).isFalse());
        }

        @Test
        @DisplayName("keep the status when the body is not an envelope")
        void nonJsonBody() {
            server.expect(requestTo(BASE + "/search"))
                    .andRespond(withStatus(HttpStatus.BAD_GATEWAY).contentType(MediaType.TEXT_HTML)
                            .body("<html>bad gateway</html>"));

            assertThatThrownBy(() -> client.search("l", "t", 1))
                    .isInstanceOf(XeyeException.class)
                    .satisfies(ex -> {
                        XeyeException xe = (XeyeException) ex;
                        assertThat(xe.status()).isEqualTo(502);
                        assertThat(xe.code()).isNull();
                        assertThat(xe.isRetryable()).isTrue();
                    });
        }

        @Test
        @DisplayName("turn a transport failure into a retryable exception with status 0")
        void transport() {
            server.expect(requestTo(BASE + "/search"))
                    .andRespond(request -> { throw new SocketTimeoutException("read timed out"); });

            assertThatThrownBy(() -> client.search("l", "t", 1))
                    .isInstanceOf(XeyeException.class)
                    .hasMessageContaining("unreachable")
                    .satisfies(ex -> {
                        XeyeException xe = (XeyeException) ex;
                        assertThat(xe.status()).isZero();
                        assertThat(xe.isRetryable()).isTrue();
                        assertThat(xe.getCause()).isNotNull();
                    });
        }

        @Test
        @DisplayName("treat 429 as retryable")
        void rateLimited() throws IOException {
            server.expect(requestTo(BASE + "/search"))
                    .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_JSON)
                            .body(error(429, "RATE_LIMITED", "slow down")));

            assertThatThrownBy(() -> client.search("l", "t", 1))
                    .isInstanceOf(XeyeException.class)
                    .satisfies(ex -> assertThat(((XeyeException) ex).isRetryable()).isTrue());
        }
    }
}
