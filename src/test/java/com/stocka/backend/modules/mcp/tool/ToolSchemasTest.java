package com.stocka.backend.modules.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.stocka.backend.modules.common.schema.Param;
import com.stocka.backend.modules.timelines.scene.SceneOp;

@DisplayName("ToolSchemas")
class ToolSchemasTest {

    enum Colour { RED, BLUE }

    record Inner(@Param("inner text") String text) {
    }

    record Sample(
            @Param(value = "the organization", required = true) String org,
            @Param("a count") Integer count,
            @Param("a ratio") Double ratio,
            @Param("a flag") boolean flag,
            @Param("a colour") Colour colour,
            @Param("ids") List<Integer> ids,
            @Param("free-form") Map<String, Object> extra,
            @Param("nested") Inner nested,
            @Param("when") Instant when,
            String undocumented) {
    }

    record WithOps(@Param(value = "ops", required = true) List<SceneOp> ops) {
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> props(Map<String, Object> schema) {
        return (Map<String, Object>) schema.get("properties");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> m(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> l(Object value) {
        return (List<Object>) value;
    }

    @Nested
    @DisplayName("records")
    class Records {

        @Test
        @DisplayName("components become typed properties with descriptions and required flags")
        void basics() {
            Map<String, Object> schema = ToolSchemas.forRecord(Sample.class);
            assertThat(schema).containsEntry("type", "object").containsEntry("additionalProperties", false)
                    .containsEntry("required", List.of("org"));
            Map<String, Object> p = props(schema);
            assertThat(p.keySet()).containsExactly("org", "count", "ratio", "flag", "colour", "ids", "extra", "nested",
                    "when", "undocumented");
            assertThat(m(p.get("org"))).containsEntry("type", "string").containsEntry("description", "the organization");
            assertThat(m(p.get("count"))).containsEntry("type", List.of("integer", "null"));
            assertThat(m(p.get("ratio"))).containsEntry("type", List.of("number", "null"));
            assertThat(m(p.get("flag"))).containsEntry("type", "boolean");
            assertThat(m(p.get("colour"))).containsEntry("enum", List.of("RED", "BLUE"));
            assertThat(m(p.get("when"))).containsEntry("format", "date-time");
            assertThat(m(p.get("undocumented"))).containsEntry("type", List.of("string", "null")).doesNotContainKey("description");
        }

        @Test
        @DisplayName("lists, maps and nested records")
        void containers() {
            Map<String, Object> p = props(ToolSchemas.forRecord(Sample.class));
            Map<String, Object> ids = m(p.get("ids"));
            assertThat(ids).containsEntry("type", List.of("array", "null"));
            assertThat(m(ids.get("items"))).containsEntry("type", "integer");
            assertThat(m(p.get("extra"))).containsEntry("additionalProperties", true);
            Map<String, Object> nested = m(p.get("nested"));
            assertThat(nested).containsEntry("type", List.of("object", "null"));
            assertThat(m(m(nested.get("properties")).get("text"))).containsEntry("description", "inner text");
        }
    }

    @Nested
    @DisplayName("polymorphic operations")
    class Polymorphic {

        @Test
        @DisplayName("a sealed interface with JsonSubTypes becomes oneOf with an op const per variant")
        void oneOf() {
            Map<String, Object> p = props(ToolSchemas.forRecord(WithOps.class));
            Map<String, Object> ops = m(p.get("ops"));
            assertThat(ops).containsEntry("type", "array");
            Map<String, Object> output = ToolSchemas.forOutput(Sample.class);
            assertThat(output).containsEntry("additionalProperties", true);
            assertThat(m(props(output).get("org"))).containsEntry("type", List.of("string", "null"));
            Map<String, Object> items = m(ops.get("items"));
            List<Object> variants = l(items.get("oneOf"));
            assertThat(variants).hasSize(16);
            Map<String, Object> setSchedule = variants.stream().map(ToolSchemasTest::m)
                    .filter(v -> "set_schedule".equals(m(m(v.get("properties")).get("op")).get("const")))
                    .findFirst().orElseThrow();
            assertThat(l(setSchedule.get("required"))).containsExactly("op");
            Map<String, Object> schedule = m(m(setSchedule.get("properties")).get("schedule"));
            assertThat(m(schedule.get("properties")).keySet()).contains("rule", "windows");
            Map<String, Object> addClip = variants.stream().map(ToolSchemasTest::m)
                    .filter(v -> "add_clip".equals(m(m(v.get("properties")).get("op")).get("const")))
                    .findFirst().orElseThrow();
            assertThat(l(addClip.get("required"))).containsExactly("op", "action");
            assertThat(m(addClip.get("properties")).keySet()).contains("itemId", "startMs", "params");
        }
    }
}
