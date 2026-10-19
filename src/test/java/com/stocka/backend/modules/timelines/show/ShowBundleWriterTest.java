package com.stocka.backend.modules.timelines.show;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.stocka.backend.modules.contract.OutputCatalog;
import com.stocka.backend.modules.storage.R2ObjectNotFoundException;
import com.stocka.backend.modules.storage.R2Service;
import com.stocka.backend.modules.timelines.scene.SceneDocument;
import com.stocka.backend.modules.timelines.scene.SceneDocument.Clip;
import com.stocka.backend.modules.timelines.scene.SceneDocument.Item;
import com.stocka.backend.modules.timelines.scene.SceneDocument.Track;
import com.stocka.backend.modules.timelines.show.ShowBundle.BundleAsset;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("ShowBundleWriter")
class ShowBundleWriterTest {

    private static final Instant NOW = Instant.parse("2026-10-20T18:00:00.750Z");
    private static final byte[] AUDIO = "ID3\u0003\u0000fake mp3 bytes".getBytes(StandardCharsets.ISO_8859_1);

    private final JsonMapper json = JsonMapper.builder().build();
    private R2Service r2;
    private ShowBundleWriter writer;
    private ShowPackage pkg;

    /** A zip entry as read back: name, method, bytes. */
    private record Entry(String name, int method, byte[] bytes) {
    }

    @BeforeEach
    void setUp() {
        r2 = mock(R2Service.class);
        writer = new ShowBundleWriter(r2);
        pkg = packageWithAudio();
    }

    /** The sample package with the audio clip pointing at attachment 12, so meta.assets lists it. */
    private static ShowPackage packageWithAudio() {
        List<Item> items = Belen2026Fixture.items();
        List<Track> tracks = Belen2026Fixture.tracks();
        List<Clip> clips = new ArrayList<>(Belen2026Fixture.clips());
        clips.replaceAll(c -> c.id().equals("c06")
                ? new Clip(c.id(), c.trackId(), c.pieceTypeActionId(), c.startMs(), c.durationMs(), Map.of("audio_name", "12-nature.mp3"), null)
                : c);
        SceneDocument doc = Belen2026Fixture.document(items, tracks, clips);
        ShowAudioFile nature = new ShowAudioFile(12, "nature.mp3", "audio/mpeg", AUDIO.length, 61000L);
        ShowBuildResult result = new ShowPackageBuilder(new OutputCatalog()).build(Belen2026Fixture.input(doc)
                .withTimeline(Belen2026Fixture.TIMELINE_NAME, 7).withAudioByPiece(Map.of(8, List.of(nature))));
        assertThat(result.errors()).isEmpty();
        return result.pkg();
    }

    private List<Entry> unzip(byte[] zip) throws IOException {
        List<Entry> out = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                out.add(new Entry(entry.getName(), entry.getMethod(), in.readAllBytes()));
            }
        }
        return out;
    }

    private byte[] write(ShowBundle bundle) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writer.write(bundle, out, NOW);
        return out.toByteArray();
    }

    @Nested
    @DisplayName("file name")
    class FileName {

        @ParameterizedTest(name = "slugify({0}) = {1}")
        @CsvSource(value = {"'Pessebre 2026 (mostra)'|pessebre-2026-mostra", "'Belén 2026!'|bel-n-2026", "'   '|show", "'---'|show",
                "Nadal_2026|nadal-2026", "'--Nadal--'|nadal", "''|show"}, delimiter = '|')
        void slugify(String name, String expected) {
            assertThat(ShowBundleWriter.slugify(name)).isEqualTo(expected);
        }

        @Test
        @DisplayName("null slugifies to show")
        void slugifyNull() {
            assertThat(ShowBundleWriter.slugify(null)).isEqualTo("show");
        }

        @Test
        @DisplayName("the bundle is named after the slug and the first eight hex of the hash")
        void bundleName() {
            String name = ShowBundleWriter.fileName(pkg);
            assertThat(name).isEqualTo("pessebre-2026-mostra-" + pkg.meta().hash().substring(7, 15) + ".belen.zip");
            assertThat(name).matches("[a-z0-9-]+-[0-9a-f]{8}\\.belen\\.zip");
        }
    }

    @Nested
    @DisplayName("zip content")
    class Content {

        @Test
        @DisplayName("writes show.json deflated, assets stored, manifest.json last")
        void entries() throws IOException {
            when(r2.open("org/1/piece/8/nature.mp3")).thenReturn(new ByteArrayInputStream(AUDIO));
            ShowBundle bundle = new ShowBundle(pkg, ShowBundleWriter.fileName(pkg),
                    List.of(new BundleAsset("assets/audio/12-nature.mp3", "audio", "12-nature.mp3", "org/1/piece/8/nature.mp3")));
            List<Entry> entries = unzip(write(bundle));
            assertThat(entries.stream().map(Entry::name).toList()).containsExactly("show.json", "assets/audio/12-nature.mp3", "manifest.json");
            assertThat(entries.get(0).method()).isEqualTo(ZipEntry.DEFLATED);
            assertThat(entries.get(1).method()).isEqualTo(ZipEntry.STORED);
            assertThat(entries.get(2).method()).isEqualTo(ZipEntry.DEFLATED);
            assertThat(entries.get(1).bytes()).isEqualTo(AUDIO);
            assertThat(new String(entries.get(0).bytes(), StandardCharsets.UTF_8)).isEqualTo(ShowCanonicalJson.pretty(pkg) + "\n");
        }

        @Test
        @DisplayName("show.json re-parses to the same package and hash")
        void showJson() throws IOException {
            ShowBundle bundle = new ShowBundle(pkg, ShowBundleWriter.fileName(pkg), List.of());
            List<Entry> entries = unzip(write(bundle));
            JsonNode show = json.readTree(entries.get(0).bytes());
            assertThat(show.path("meta").path("hash").asText()).isEqualTo(pkg.meta().hash());
            assertThat(ShowCanonicalJson.showHash(show)).isEqualTo(pkg.meta().hash());
            assertThat(show.path("meta").path("assets").get(0).path("attachmentId").asInt()).isEqualTo(12);
            assertThat(show.path("format").asText()).isEqualTo("stocka-show");
        }

        @Test
        @DisplayName("the manifest lists every file with its sha256, size and kind, in schema order")
        void manifest() throws IOException {
            when(r2.open(anyString())).thenReturn(new ByteArrayInputStream(AUDIO));
            ShowBundle bundle = new ShowBundle(pkg, ShowBundleWriter.fileName(pkg),
                    List.of(new BundleAsset("assets/audio/12-nature.mp3", "audio", "12-nature.mp3", "k")));
            List<Entry> entries = unzip(write(bundle));
            JsonNode manifest = json.readTree(entries.get(2).bytes());
            List<String> keys = new ArrayList<>();
            manifest.propertyNames().forEach(keys::add);
            assertThat(keys).containsExactly("format", "version", "show", "files", "createdAt", "createdBy");
            assertThat(manifest.path("format").asText()).isEqualTo("stocka-bundle");
            assertThat(manifest.path("version").asInt()).isEqualTo(1);
            assertThat(manifest.path("show").path("hash").asText()).isEqualTo(pkg.meta().hash());
            assertThat(manifest.path("show").path("name").asText()).isEqualTo(Belen2026Fixture.TIMELINE_NAME);
            assertThat(manifest.path("show").path("timelineId").asInt()).isEqualTo(7);
            assertThat(manifest.path("show").path("board").asText()).isEqualTo("rpi4b");
            assertThat(manifest.path("createdAt").asText()).isEqualTo("2026-10-20T18:00:00Z");
            assertThat(manifest.path("createdBy").asText()).isEqualTo("backend");
            assertThat(manifest.has("activate")).isFalse();
            JsonNode files = manifest.path("files");
            assertThat(files.size()).isEqualTo(2);
            assertThat(files.get(0).path("path").asText()).isEqualTo("show.json");
            assertThat(files.get(0).path("kind").asText()).isEqualTo("show");
            assertThat(files.get(0).path("size").asLong()).isEqualTo(entries.get(0).bytes().length);
            assertThat(files.get(0).path("sha256").asText()).isEqualTo(ShowCanonicalJson.sha256Hex(entries.get(0).bytes()));
            assertThat(files.get(1).path("path").asText()).isEqualTo("assets/audio/12-nature.mp3");
            assertThat(files.get(1).path("kind").asText()).isEqualTo("audio");
            assertThat(files.get(1).path("size").asLong()).isEqualTo(AUDIO.length);
            assertThat(files.get(1).path("sha256").asText()).isEqualTo(ShowCanonicalJson.sha256Hex(AUDIO));
            assertThat(new String(entries.get(2).bytes(), StandardCharsets.UTF_8)).endsWith("}\n");
        }

        @Test
        @DisplayName("a timeline without id writes timelineId null in the manifest")
        void nullTimelineId() throws IOException {
            ShowPackage noId = new ShowPackageBuilder(new OutputCatalog()).build(Belen2026Fixture.input()).pkg();
            Map<String, Object> manifest = ShowBundleWriter.manifest(noId, List.of(), NOW);
            @SuppressWarnings("unchecked")
            Map<String, Object> show = (Map<String, Object>) manifest.get("show");
            assertThat(show).containsEntry("timelineId", null);
            assertThat(ShowCanonicalJson.pretty(manifest)).contains("\"timelineId\": null");
        }

        @Test
        @DisplayName("a bundle without assets holds only the show and the manifest")
        void noAssets() throws IOException {
            List<Entry> entries = unzip(write(new ShowBundle(pkg, "x.belen.zip", List.of())));
            assertThat(entries.stream().map(Entry::name).toList()).containsExactly("show.json", "manifest.json");
        }

        @Test
        @DisplayName("video assets go under assets/video with kind video")
        void videoAssets() throws IOException {
            when(r2.open("v")).thenReturn(new ByteArrayInputStream(new byte[] {1, 2, 3}));
            ShowBundle bundle = new ShowBundle(pkg, "x.belen.zip", List.of(new BundleAsset("assets/video/3-clip.mp4", "video", "3-clip.mp4", "v")));
            List<Entry> entries = unzip(write(bundle));
            assertThat(entries.get(1).name()).isEqualTo("assets/video/3-clip.mp4");
            JsonNode manifest = json.readTree(entries.get(2).bytes());
            assertThat(manifest.path("files").get(1).path("kind").asText()).isEqualTo("video");
            assertThat(manifest.path("files").get(1).path("size").asInt()).isEqualTo(3);
        }

        @Test
        @DisplayName("a missing object in storage propagates as the storage exception")
        void missingObject() {
            when(r2.open("gone")).thenThrow(new R2ObjectNotFoundException("gone"));
            ShowBundle bundle = new ShowBundle(pkg, "x.belen.zip", List.of(new BundleAsset("assets/audio/1-a.mp3", "audio", "1-a.mp3", "gone")));
            assertThatThrownBy(() -> write(bundle)).isInstanceOf(R2ObjectNotFoundException.class);
        }

        @Test
        @DisplayName("the manifest file entries keep the order they are passed")
        void manifestOrder() {
            List<Map<String, Object>> files = new ArrayList<>();
            Map<String, Object> show = new LinkedHashMap<>();
            show.put("path", "show.json");
            files.add(show);
            Map<String, Object> manifest = ShowBundleWriter.manifest(pkg, files, NOW);
            assertThat(manifest.get("files")).isSameAs(files);
        }
    }
}
