package com.stocka.backend.modules.timelines.show;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.stocka.backend.modules.timelines.show.AudioAssets.AudioFileWindow;

@DisplayName("AudioAssets")
class AudioAssetsTest {

    private final ShowAudioFile nature = new ShowAudioFile(12, "Nature Sounds.MP3", "audio/mpeg", 2048, 61000L);

    @Nested
    @DisplayName("names")
    class Names {

        @Test
        @DisplayName("makes the file name safe and prefixes the attachment id")
        void assetName() {
            assertThat(AudioAssets.safeAssetFilename("Nature Sounds.MP3")).isEqualTo("nature_sounds.mp3");
            assertThat(AudioAssets.safeAssetFilename("  ")).isEqualTo("file");
            assertThat(AudioAssets.safeAssetFilename(null)).isEqualTo("file");
            assertThat(AudioAssets.safeAssetFilename("cançó-nadal_1.ogg")).isEqualTo("can__-nadal_1.ogg");
            assertThat(AudioAssets.assetNameFor(12, "Nature Sounds.MP3")).isEqualTo("12-nature_sounds.mp3");
            assertThat(nature.assetName()).isEqualTo("12-nature_sounds.mp3");
        }

        @Test
        @DisplayName("reads the attachment id at the front of an asset name")
        void attachmentId() {
            assertThat(AudioAssets.attachmentIdFromAssetName("12-nature.mp3")).isEqualTo(12);
            assertThat(AudioAssets.attachmentIdFromAssetName(" 7-x ")).isEqualTo(7);
            assertThat(AudioAssets.attachmentIdFromAssetName("nature.mp3")).isNull();
            assertThat(AudioAssets.attachmentIdFromAssetName("-nature.mp3")).isNull();
            assertThat(AudioAssets.attachmentIdFromAssetName(12)).isNull();
            assertThat(AudioAssets.attachmentIdFromAssetName(null)).isNull();
            assertThat(AudioAssets.attachmentIdFromAssetName("99999999999-x.mp3")).isNull();
        }

        @Test
        @DisplayName("finds the attachment an asset name points at")
        void find() {
            assertThat(AudioAssets.findAudioByAssetName(List.of(nature), "12-nature.mp3")).isEqualTo(nature);
            assertThat(AudioAssets.findAudioByAssetName(List.of(nature), "13-nature.mp3")).isNull();
            assertThat(AudioAssets.findAudioByAssetName(List.of(nature), "nature.mp3")).isNull();
            assertThat(AudioAssets.findAudioByAssetName(null, "12-nature.mp3")).isNull();
        }
    }

    @Nested
    @DisplayName("windows")
    class Windows {

        @Test
        @DisplayName("formats a length as a clock")
        void clock() {
            assertThat(AudioAssets.formatClock(61000L)).isEqualTo("1:01");
            assertThat(AudioAssets.formatClock(3_725_000L)).isEqualTo("1:02:05");
            assertThat(AudioAssets.formatClock(499L)).isEqualTo("0:00");
            assertThat(AudioAssets.formatClock(null)).isEqualTo("—");
            assertThat(AudioAssets.formatClock(-1L)).isEqualTo("—");
        }

        @Test
        @DisplayName("cuts the window at the end of the file and measures what falls beyond it")
        void window() {
            assertThat(AudioAssets.audioFileWindow(50, 20.0, 61000L)).isEqualTo(new AudioFileWindow(50, 61.0, 9.0));
            assertThat(AudioAssets.audioFileWindow(0, 10.0, 61000L)).isEqualTo(new AudioFileWindow(0, 10.0, 0));
            assertThat(AudioAssets.audioFileWindow(5, null, 61000L)).isEqualTo(new AudioFileWindow(5, 61.0, 0));
            assertThat(AudioAssets.audioFileWindow(70, null, 61000L)).isEqualTo(new AudioFileWindow(61.0, 61.0, 0));
            assertThat(AudioAssets.audioFileWindow(2, 3.0, null)).isEqualTo(new AudioFileWindow(2, 5.0, 0));
            assertThat(AudioAssets.audioFileWindow(2, null, 0L)).isEqualTo(new AudioFileWindow(2, null, 0));
        }
    }
}
