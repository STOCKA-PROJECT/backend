package com.stocka.backend.modules.mcp.resource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.stocka.backend.modules.contract.OutputCatalog;

import tools.jackson.databind.json.JsonMapper;

/**
 * Static reference material: the usage guide, the output catalog (kinds, actions, parameters, board)
 * and the JSON Schema of the timeline scene document. Read once from the classpath.
 */
@Component
public class ReferenceResources implements ResourceSource {

    /** URI of the guide. */
    public static final String GUIDE_URI = "stocka://guide";
    /** URI of the output catalog. */
    public static final String CATALOG_URI = "stocka://catalog";
    /** URI of the scene schema. */
    public static final String SCENE_SCHEMA_URI = "stocka://schema/scene";

    private final String guide;
    private final String sceneSchema;
    private final String catalog;

    public ReferenceResources(OutputCatalog outputCatalog, JsonMapper json) {
        this.guide = load("mcp/guide.md");
        this.sceneSchema = load("mcp/scene.schema.json");
        this.catalog = json.writeValueAsString(outputCatalog.raw());
    }

    static String load(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Missing classpath resource " + path, e);
        }
    }

    @Override
    public List<ResourceDefinition> resources() {
        return List.of(
                new ResourceDefinition(GUIDE_URI, "Stocka MCP guide",
                        "How to use the Stocka tools: recommended workflows, ids and versions, limits.",
                        "text/markdown", caller -> guide),
                new ResourceDefinition(CATALOG_URI, "Output catalog",
                        "Hardware output kinds (relay, gpio_expander, led_strip, led_segment, motor, audio, moving_head, button), "
                                + "their actions and parameters, and the Raspberry Pi board profile. Needed to "
                                + "write clip parameters and port configurations.",
                        "application/json", caller -> catalog),
                new ResourceDefinition(SCENE_SCHEMA_URI, "Timeline scene schema",
                        "JSON Schema of the timeline scene document (board, layers, items, tracks, clips) with "
                                + "the semantics of every field.",
                        "application/json", caller -> sceneSchema));
    }
}
