package com.stocka.backend.modules.timelines.scene;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.stocka.backend.modules.common.error.ApiException;
import com.stocka.backend.modules.common.error.ErrorCodes;

import tools.jackson.databind.JsonNode;

@DisplayName("SceneJsonCodec")
class SceneJsonCodecTest {

    private final SceneJsonCodec codec = new SceneJsonCodec();

    private static final String EDITOR_DOCUMENT = """
            {"schemaVersion":1,"board":{"width":1280,"height":720,"background":null,"grid":true},
             "layers":[{"id":"layer-1","name":"Capa 1","z":0,"visible":true,"locked":false,"opacity":1,"color":"#fff"}],
             "items":[{"id":"item-a","pieceId":5,"layerId":"layer-1","x":10,"y":20,"width":100,"height":50,"rotation":0,
                       "ports":[7],"ledPath":[{"x":0,"y":0.5},{"x":1,"y":0.5}],"customFlag":true}],
             "tracks":[{"id":"track-a","itemId":"item-a","name":"A","muted":false}],
             "clips":[{"id":"clip-a","trackId":"track-a","pieceTypeActionId":9,"startMs":0,"durationMs":500,
                       "paramValues":{"color":"[1,2,3]"},"label":"x","collapsed":true}],
             "durationMs":30000,"fps":30,"stripOrder":{"7":["item-a"]},"editorPrefs":{"zoom":2}}
            """;

    @Nested
    @DisplayName("parse")
    class Parse {

        @Test
        @DisplayName("null yields the empty scene")
        void empty() {
            SceneDocument doc = codec.parse(null);
            assertThat(doc.layers()).hasSize(1);
            assertThat(doc.durationMs()).isEqualTo(SceneDocument.DEFAULT_DURATION_MS);
        }

        @Test
        @DisplayName("an editor document is read, ignoring the fields the server does not model")
        void parses() {
            SceneDocument doc = codec.parse(codec.mapper().readTree(EDITOR_DOCUMENT));
            assertThat(doc.items()).hasSize(1);
            assertThat(doc.items().get(0).ports()).containsExactly(7);
            assertThat(doc.items().get(0).ledPath()).hasSize(2);
            assertThat(doc.clips().get(0).paramValues()).containsEntry("color", "[1,2,3]");
            assertThat(doc.stripOrder()).containsEntry("7", List.of("item-a"));
        }

        @Test
        @DisplayName("a newer schema version is refused")
        void newerSchema() {
            assertThatThrownBy(() -> codec.parse(codec.mapper().readTree("{\"schemaVersion\":2}")))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).getCode()).isEqualTo(ErrorCodes.TIMELINE_SCENE_SCHEMA_UNSUPPORTED);
        }

        @Test
        @DisplayName("structurally wrong JSON is a document_invalid error")
        void invalid() {
            assertThatThrownBy(() -> codec.parse(codec.mapper().readTree("{\"items\":\"nope\"}")))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).getCode()).isEqualTo(ErrorCodes.TIMELINE_SCENE_DOCUMENT_INVALID);
        }
    }

    @Nested
    @DisplayName("toJson")
    class ToJson {

        @Test
        @DisplayName("fields unknown to the server survive a round trip, at every level")
        void preservesUnknown() {
            JsonNode original = codec.mapper().readTree(EDITOR_DOCUMENT);
            SceneDocument doc = codec.parse(original);
            SceneDocument edited = new SceneDocument(doc.schemaVersion(), doc.board(), doc.layers(), doc.items(), doc.tracks(),
                    doc.clips(), 45_000, doc.fps(), doc.stripOrder(), doc.schedule());
            JsonNode out = codec.toJson(edited, original);
            assertThat(out.path("durationMs").asLong()).as("the show lasts until the end of its last clip")
                    .isEqualTo(SceneDocument.endOfLastClip(doc.clips()).orElseThrow());
            assertThat(out.path("editorPrefs").path("zoom").asInt()).isEqualTo(2);
            assertThat(out.path("board").path("grid").asBoolean()).isTrue();
            assertThat(out.path("layers").get(0).path("color").asText()).isEqualTo("#fff");
            assertThat(out.path("items").get(0).path("customFlag").asBoolean()).isTrue();
            assertThat(out.path("tracks").get(0).path("muted").asBoolean()).isFalse();
            assertThat(out.path("clips").get(0).path("collapsed").asBoolean()).isTrue();
        }

        @Test
        @DisplayName("removed elements do not resurrect and nulls are omitted")
        void noResurrection() {
            JsonNode original = codec.mapper().readTree(EDITOR_DOCUMENT);
            SceneDocument doc = codec.parse(original);
            SceneDocument edited = new SceneDocument(doc.schemaVersion(), doc.board(), doc.layers(), doc.items(), doc.tracks(),
                    List.of(), doc.durationMs(), doc.fps(), Map.of(), doc.schedule());
            JsonNode out = codec.toJson(edited, original);
            assertThat(out.path("clips")).isEmpty();
            assertThat(out.path("items").get(0).has("widthAttr")).isFalse();
        }

        @Test
        @DisplayName("the button schedule is read, kept and removed when set to null")
        void schedule() {
            JsonNode original = codec.mapper().readTree(EDITOR_DOCUMENT.replace("\"editorPrefs\"",
                    "\"schedule\":{\"rule\":\"block\",\"windows\":[{\"days\":[\"sun\"],\"from\":\"13:00\",\"to\":\"16:00\"}]},\"editorPrefs\""));
            SceneDocument doc = codec.parse(original);
            assertThat(doc.schedule()).isEqualTo(new SceneDocument.Schedule("block",
                    List.of(new SceneDocument.ScheduleWindow(List.of("sun"), "13:00", "16:00"))));
            assertThat(codec.toJson(doc, original).path("schedule").path("rule").asText()).isEqualTo("block");
            JsonNode cleared = codec.toJson(doc.withSchedule(null), original);
            assertThat(cleared.has("schedule")).isFalse();
            assertThat(cleared.path("editorPrefs").path("zoom").asInt()).isEqualTo(2);
        }

        @Test
        @DisplayName("a document without schedule is written without one")
        void noSchedule() {
            JsonNode original = codec.mapper().readTree(EDITOR_DOCUMENT);
            SceneDocument doc = codec.parse(original);
            assertThat(doc.schedule()).isNull();
            assertThat(codec.toJson(doc, original).has("schedule")).isFalse();
        }

        @Test
        @DisplayName("without an original the typed document is written as is")
        void fresh() {
            JsonNode out = codec.toJson(SceneDocument.empty(1000, null), null);
            assertThat(out.path("schemaVersion").asInt()).isEqualTo(1);
            assertThat(out.path("layers").get(0).path("name").asText()).isEqualTo("Capa 1");
        }
    }


    @Nested
    @DisplayName("portTypes")
    class PortTypes {

        private SceneDocument parse(String portTypes) {
            return codec.parse(codec.mapper().readTree(EDITOR_DOCUMENT.replace("\"ports\":[7],", "\"ports\":[7,8],\"portTypes\":" + portTypes + ",")));
        }

        private JsonNode written(String portTypes) {
            JsonNode original = codec.mapper().readTree(EDITOR_DOCUMENT.replace("\"ports\":[7],", "\"ports\":[7,8],\"portTypes\":" + portTypes + ","));
            return codec.toJson(codec.parse(original), original).path("items").get(0).path("portTypes");
        }

        @Test
        @DisplayName("the type each port plays is read with integer keys and written back as a number; absent when empty")
        void roundTrip() {
            SceneDocument doc = parse("{\"8\":3}");
            assertThat(doc.items().get(0).portTypes()).containsExactly(Map.entry(8, List.of(3)));
            assertThat(doc.items().get(0).portRoles(8, 2)).containsExactly(3);
            assertThat(doc.items().get(0).portRoles(7, 2)).containsExactly(2);
            assertThat(doc.items().get(0).portRoles(7, null)).isEmpty();
            JsonNode out = written("{\"8\":3}");
            assertThat(out.path("8").isNumber()).isTrue();
            assertThat(out.path("8").asInt()).isEqualTo(3);

            SceneDocument plain = codec.parse(codec.mapper().readTree(EDITOR_DOCUMENT));
            assertThat(plain.items().get(0).portTypes()).isEmpty();
            assertThat(codec.toJson(plain, null).path("items").get(0).has("portTypes")).isFalse();
        }

        @Test
        @DisplayName("a list of types makes the port play all of them; one element or duplicates collapse to a number")
        void listOfRoles() {
            SceneDocument doc = parse("{\"8\":[3,4]}");
            assertThat(doc.items().get(0).portTypes()).containsExactly(Map.entry(8, List.of(3, 4)));
            assertThat(doc.items().get(0).portRoles(8, 2)).containsExactly(3, 4);
            JsonNode out = written("{\"8\":[3,4]}");
            assertThat(out.path("8").isArray()).isTrue();
            assertThat(out.path("8")).extracting(JsonNode::asInt).containsExactly(3, 4);

            assertThat(written("{\"8\":[3]}").path("8").isNumber()).isTrue();
            assertThat(written("{\"8\":[3,3]}").path("8").asInt()).isEqualTo(3);
            assertThat(parse("{\"8\":[]}").items().get(0).portRoles(8, 2)).as("an empty list falls back to the port's own type").containsExactly(2);
            assertThat(parse("{\"8\":[3],\"7\":[4,5]}").items().get(0).portTypes())
                    .containsExactly(Map.entry(8, List.of(3)), Map.entry(7, List.of(4, 5)));
        }

        @Test
        @DisplayName("a value that is neither a type id nor a list of them, or a key that is not a port id, is a 400")
        void invalid() {
            for (String bad : List.of("{\"8\":\"Llum\"}", "{\"8\":[3,\"x\"]}", "{\"8\":{\"id\":3}}", "{\"eight\":3}", "[3]")) {
                assertThatThrownBy(() -> parse(bad)).as(bad)
                        .isInstanceOf(ApiException.class)
                        .extracting(e -> ((ApiException) e).getCode()).isEqualTo(ErrorCodes.TIMELINE_SCENE_DOCUMENT_INVALID);
            }
            assertThat(parse("null").items().get(0).portTypes()).isEmpty();
        }
    }
}
