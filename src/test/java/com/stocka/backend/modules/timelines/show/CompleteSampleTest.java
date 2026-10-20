package com.stocka.backend.modules.timelines.show;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.stocka.backend.modules.contract.OutputCatalog;
import com.stocka.backend.modules.timelines.show.ShowPackage.ShowAssets;
import com.stocka.backend.modules.timelines.show.ShowPackage.ShowEvent;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The complete 2026 sample ({@code rpi4-system/contract/samples/pessebre-2026-complet.show.json}:
 * expander relays, native relays with polarity, two moving heads, the angel motor) is what the
 * device accepts; the backend must agree on both the rules and the canonical hash.
 */
@DisplayName("Complete 2026 sample (contract v3)")
class CompleteSampleTest {

    private static final String RESOURCE = "/show/pessebre-2026-complet.show.json";
    private final OutputCatalog catalog = new OutputCatalog();
    private final JsonMapper json = JsonMapper.builder().build();

    private JsonNode sample() {
        try (InputStream in = CompleteSampleTest.class.getResourceAsStream(RESOURCE)) {
            return json.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("validates without errors and its hash is the one the device computed")
    void validatesAndHashes() {
        JsonNode sample = sample();
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> hardware = json.convertValue(sample.get("hardware"), Map.class);
        List<ShowEvent> events = new ArrayList<>();
        for (JsonNode ev : sample.get("timeline")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> params = json.convertValue(ev.get("params"), Map.class);
            events.add(new ShowEvent(ev.get("t").asLong(), ev.get("target").asText(), ev.get("action").asText(), params));
        }
        List<String> audio = new ArrayList<>();
        sample.path("assets").path("audio").forEach(n -> audio.add(n.asText()));
        List<ShowIssue> issues = ShowValidator.validate(hardware, events, sample.path("board").asText(),
                sample.path("durationMs").asLong(), new ShowAssets(audio), catalog);
        assertThat(issues.stream().filter(ShowIssue::isError)).isEmpty();
        assertThat(hardware).containsKeys("exp1", "Estable", "Foco", "Foco2", "Angel");
        assertThat(sample.path("version").asInt()).isEqualTo(ShowPackage.VERSION);
        assertThat(ShowCanonicalJson.showHash(sample)).isEqualTo(sample.path("meta").path("hash").asText());
    }
}
