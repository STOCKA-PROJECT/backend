package com.stocka.backend.modules.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.core.io.ByteArrayResource;

import com.stocka.backend.modules.piecetypes.entity.AttributeType;

@DisplayName("OutputCatalog")
class OutputCatalogTest {

    private static OutputCatalog catalog;

    @BeforeAll
    static void load() {
        catalog = new OutputCatalog();
    }

    @Nested
    @DisplayName("loading")
    class Loading {

        @Test
        @DisplayName("reads the bundled catalog with its version")
        void should_loadBundledCatalog() {
            assertThat(catalog.version()).isEqualTo(3);
            assertThat(catalog.raw().path("format").asText()).isEqualTo("stocka-catalog");
        }

        @ParameterizedTest
        @EnumSource(OutputKind.class)
        @DisplayName("describes every OutputKind constant with a controller")
        void should_describeEveryKind(OutputKind kind) {
            CatalogKind spec = catalog.kind(kind);
            assertThat(spec).isNotNull();
            assertThat(spec.kind()).isEqualTo(kind);
            assertThat(spec.controller()).endsWith("Controller");
        }

        @Test
        @DisplayName("fails fast on a document that is not a catalog")
        void should_rejectNonCatalog() {
            assertThatThrownBy(() -> new OutputCatalog(new ByteArrayResource("{}".getBytes(StandardCharsets.UTF_8))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("stocka-catalog");
        }

        @Test
        @DisplayName("fails fast when a kind is missing")
        void should_rejectMissingKind() {
            String partial = """
                    {"format":"stocka-catalog","version":9,"kinds":{"relay":{"controller":"RelayController","pin":true,"config":{},"actions":{}}},"boards":{}}
                    """;
            assertThatThrownBy(() -> new OutputCatalog(new ByteArrayResource(partial.getBytes(StandardCharsets.UTF_8))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("gpio_expander");
        }

        @Test
        @DisplayName("fails fast on an unknown kind key")
        void should_rejectUnknownKind() {
            String unknown = """
                    {"format":"stocka-catalog","version":1,"kinds":{"laser":{"controller":"X","config":{},"actions":{}}},"boards":{}}
                    """;
            assertThatThrownBy(() -> new OutputCatalog(new ByteArrayResource(unknown.getBytes(StandardCharsets.UTF_8))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("laser");
        }

        @Test
        @DisplayName("fails fast on malformed JSON")
        void should_rejectMalformedJson() {
            assertThatThrownBy(() -> new OutputCatalog(new ByteArrayResource("{".getBytes(StandardCharsets.UTF_8))))
                    .isInstanceOf(RuntimeException.class);
        }
    }

    @Nested
    @DisplayName("kinds")
    class Kinds {

        @Test
        @DisplayName("strips, expanders and sensors are infrastructure; the rest belong to pieces")
        void should_flagInfrastructure() {
            assertThat(catalog.isInfrastructure(OutputKind.LED_STRIP)).isTrue();
            assertThat(catalog.isInfrastructure(OutputKind.GPIO_EXPANDER)).isTrue();
            assertThat(catalog.isInfrastructure(OutputKind.SENSOR)).isTrue();
            for (OutputKind k : List.of(OutputKind.RELAY, OutputKind.LED_SEGMENT, OutputKind.MOTOR, OutputKind.AUDIO, OutputKind.MOVING_HEAD)) {
                assertThat(catalog.isInfrastructure(k)).as(k.name()).isFalse();
            }
        }

        @Test
        @DisplayName("the strip always carries a pin, the relay and the sensor optionally, the rest never")
        void should_flagPin() {
            assertThat(catalog.pinMode(OutputKind.LED_STRIP)).isEqualTo(PinMode.REQUIRED);
            assertThat(catalog.pinMode(OutputKind.SENSOR)).isEqualTo(PinMode.OPTIONAL);
            assertThat(catalog.pinMode(OutputKind.RELAY)).isEqualTo(PinMode.OPTIONAL);
            assertThat(catalog.hasPin(OutputKind.RELAY)).isTrue();
            assertThat(catalog.hasPin(OutputKind.LED_STRIP)).isTrue();
            assertThat(catalog.hasPin(OutputKind.SENSOR)).isTrue();
            for (OutputKind k : List.of(OutputKind.LED_SEGMENT, OutputKind.MOTOR, OutputKind.AUDIO, OutputKind.MOVING_HEAD, OutputKind.GPIO_EXPANDER)) {
                assertThat(catalog.hasPin(k)).as(k.name()).isFalse();
                assertThat(catalog.pinMode(k)).as(k.name()).isEqualTo(PinMode.NONE);
            }
        }

        @Test
        @DisplayName("PinMode parses the catalog values")
        void should_parsePinMode() {
            assertThat(PinMode.parse(true)).isEqualTo(PinMode.REQUIRED);
            assertThat(PinMode.parse("optional")).isEqualTo(PinMode.OPTIONAL);
            assertThat(PinMode.parse(" Optional ")).isEqualTo(PinMode.OPTIONAL);
            assertThat(PinMode.parse(false)).isEqualTo(PinMode.NONE);
            assertThat(PinMode.parse(null)).isEqualTo(PinMode.NONE);
            assertThat(PinMode.parse("yes")).isEqualTo(PinMode.NONE);
            assertThat(PinMode.OPTIONAL.allowsPin()).isTrue();
            assertThat(PinMode.NONE.allowsPin()).isFalse();
        }

        @Test
        @DisplayName("describes the moving head profiles (channels and physical ranges)")
        void should_exposeProfiles() {
            FixtureProfile varytec = catalog.movingHeadProfile("varytec-hero-wash-712z").orElseThrow();
            assertThat(varytec.channels()).isEqualTo(15);
            assertThat(varytec.panMax()).isEqualTo(540);
            assertThat(varytec.tiltMax()).isEqualTo(190);
            assertThat(varytec.zoomMin()).isEqualTo(6);
            assertThat(varytec.zoomMax()).isEqualTo(36);
            assertThat(varytec.lastChannel(1)).isEqualTo(15);
            assertThat(varytec.lastChannel(498)).isEqualTo(512);
            assertThat(catalog.movingHeadProfiles()).containsOnlyKeys("varytec-hero-wash-712z");
            assertThat(catalog.movingHeadProfile("generic")).isEmpty();
            assertThat(catalog.movingHeadProfile(null)).isEmpty();
            assertThat(catalog.configSchema(OutputKind.MOVING_HEAD).get("profile").values()).containsExactly("varytec-hero-wash-712z");
            assertThat(catalog.configSchema(OutputKind.MOVING_HEAD).get("address").max()).isEqualByComparingTo(BigDecimal.valueOf(512));
        }

        @Test
        @DisplayName("exposes the config schema with required keys, bounds, refs and defaults")
        void should_exposeConfigSchema() {
            Map<String, CatalogParam> strip = catalog.configSchema(OutputKind.LED_STRIP);
            assertThat(strip.keySet()).containsExactly("dma", "channel", "length", "brightness", "freq_hz", "invert");
            assertThat(strip.get("dma").required()).isTrue();
            assertThat(strip.get("dma").max()).isEqualByComparingTo(BigDecimal.valueOf(14));
            assertThat(strip.get("brightness").required()).isFalse();
            assertThat(strip.get("brightness").defaultValue()).isEqualTo("255");
            assertThat(strip.get("invert").type()).isEqualTo(CatalogType.BOOL);

            Map<String, CatalogParam> segment = catalog.configSchema(OutputKind.LED_SEGMENT);
            assertThat(segment.get("strip").ref()).isEqualTo("led_strip");
            assertThat(segment.get("first").type()).isEqualTo(CatalogType.INT);

            Map<String, CatalogParam> motor = catalog.configSchema(OutputKind.MOTOR);
            assertThat(motor.get("maxTimeForward").required()).isTrue();
            assertThat(motor.get("maxTimeReverse").required()).isTrue();
            assertThat(motor.get("limitForward").ref()).isEqualTo("sensor");
            assertThat(motor.get("limitReverse").ref()).isEqualTo("sensor");
            assertThat(motor.get("expander").ref()).isEqualTo("gpio_expander");
            assertThat(motor.get("switchDelayMs").defaultValue()).isEqualTo("50");
            assertThat(motor.get("pinForward").required()).isFalse();
            Map<String, CatalogParam> sensor = catalog.configSchema(OutputKind.SENSOR);
            assertThat(sensor.get("onActive").type()).isEqualTo(CatalogType.ENUM);
            assertThat(sensor.get("onActive").values()).containsExactly("none", "play", "stop_show", "panic");
            assertThat(sensor.get("pull").values()).containsExactly("up", "down", "none");
            assertThat(sensor.get("activeLow").defaultValue()).isEqualTo("true");
            assertThat(catalog.configSchema(OutputKind.AUDIO)).isEmpty();
            Map<String, CatalogParam> relay = catalog.configSchema(OutputKind.RELAY);
            assertThat(relay.keySet()).containsExactly("expander", "channel", "activeLow");
            assertThat(relay.get("expander").ref()).isEqualTo("gpio_expander");
            assertThat(relay.get("expander").required()).isFalse();
            assertThat(relay.get("channel").max()).isEqualByComparingTo(BigDecimal.valueOf(15));
            assertThat(relay.get("activeLow").type()).isEqualTo(CatalogType.BOOL);
            Map<String, CatalogParam> expander = catalog.configSchema(OutputKind.GPIO_EXPANDER);
            assertThat(expander.keySet()).containsExactly("chip", "bus", "address", "activeLow");
            assertThat(expander.get("chip").values()).containsExactly("mcp23017");
            assertThat(expander.get("address").min()).isEqualByComparingTo(BigDecimal.valueOf(32));
            assertThat(expander.get("address").max()).isEqualByComparingTo(BigDecimal.valueOf(39));
            assertThat(catalog.kind(OutputKind.GPIO_EXPANDER).actions()).isEmpty();
            assertThat(catalog.kind(OutputKind.RELAY).actions().keySet()).containsExactly("turn_on", "turn_off", "pulse");
            assertThat(catalog.kind(OutputKind.MOVING_HEAD).actions().keySet()).containsExactly("set", "blackout");
        }
    }

    @Nested
    @DisplayName("actions")
    class Actions {

        @Test
        @DisplayName("finds an action of a kind with its parameters")
        void should_findAction() {
            CatalogAction turnOn = catalog.action(OutputKind.RELAY, "turn_on").orElseThrow();
            assertThat(turnOn.params()).containsKey("time");
            assertThat(turnOn.params().get("time").duration()).isTrue();
            assertThat(turnOn.params().get("time").type()).isEqualTo(CatalogType.NUMBER);
            assertThat(turnOn.continuous()).isFalse();
        }

        @Test
        @DisplayName("returns empty for an action of another kind, an unknown action or null")
        void should_returnEmpty() {
            assertThat(catalog.action(OutputKind.RELAY, "fill")).isEmpty();
            assertThat(catalog.action(OutputKind.RELAY, "nope")).isEmpty();
            assertThat(catalog.action(OutputKind.RELAY, null)).isEmpty();
        }

        @Test
        @DisplayName("the sensor has no show actions; the motor turns forward, reverse or stops")
        void should_haveSensorAndMotorActions() {
            assertThat(catalog.kind(OutputKind.SENSOR).actions()).isEmpty();
            assertThat(catalog.kind(OutputKind.MOTOR).actions().keySet()).containsExactly("forward", "reverse", "stop");
            assertThat(catalog.action(OutputKind.MOTOR, "start_up")).isEmpty();
        }

        @Test
        @DisplayName("a strip is a whole-strip target: it shares the LED actions of a segment plus setBrightness")
        void should_exposeStripActions() {
            assertThat(catalog.kind(OutputKind.LED_STRIP).actions().keySet())
                    .containsExactlyInAnyOrder("fill", "clear", "animate", "setBrightness");
            CatalogAction stripFill = catalog.action(OutputKind.LED_STRIP, "fill").orElseThrow();
            CatalogAction segmentFill = catalog.action(OutputKind.LED_SEGMENT, "fill").orElseThrow();
            assertThat(stripFill.params().keySet()).isEqualTo(segmentFill.params().keySet());
            CatalogAction stripAnimate = catalog.action(OutputKind.LED_STRIP, "animate").orElseThrow();
            CatalogAction segmentAnimate = catalog.action(OutputKind.LED_SEGMENT, "animate").orElseThrow();
            assertThat(stripAnimate.continuous()).isTrue();
            assertThat(stripAnimate.animations().keySet()).isEqualTo(segmentAnimate.animations().keySet());
            assertThat(catalog.action(OutputKind.LED_SEGMENT, "setBrightness")).isEmpty();
        }

        @Test
        @DisplayName("marks asset parameters and unsupported controller parameters")
        void should_exposeAssetsAndUnsupported() {
            CatalogAction play = catalog.action(OutputKind.AUDIO, "play").orElseThrow();
            assertThat(play.params().get("audio_name").asset()).isEqualTo("audio");
            assertThat(play.params().get("audio_name").required()).isTrue();
            assertThat(catalog.action(OutputKind.AUDIO, "stop").orElseThrow().unsupported()).containsExactly("player_id");
        }
    }

    @Nested
    @DisplayName("animate parameters")
    class AnimateParams {

        private CatalogAction animate() {
            return catalog.action(OutputKind.LED_SEGMENT, "animate").orElseThrow();
        }

        @Test
        @DisplayName("is continuous and lists every animation")
        void should_listAnimations() {
            assertThat(animate().continuous()).isTrue();
            assertThat(animate().animations().keySet()).containsExactlyInAnyOrder(
                    "stars", "fire", "window_light", "candle", "moonlight", "clouds", "shooting_star",
                    "aurora", "snow", "fade", "sunrise", "sunset");
            assertThat(animate().params().get("animation_type").values()).containsExactlyInAnyOrderElementsOf(
                    animate().animations().keySet());
        }

        @Test
        @DisplayName("allParams is the union of the common and every animation's parameters")
        void should_unionAllParams() {
            assertThat(animate().allParams().keySet())
                    .contains("animation_type", "first", "last", "duration", "color", "from_color", "density", "interval", "background");
        }

        @Test
        @DisplayName("paramsFor restricts to one animation and falls back to the common ones")
        void should_restrictPerAnimation() {
            Map<String, CatalogParam> fade = animate().paramsFor("fade");
            assertThat(fade.keySet()).containsExactlyInAnyOrder("animation_type", "first", "last", "duration", "color", "from_color", "waypoints");
            assertThat(fade.get("color").required()).isTrue();
            assertThat(animate().paramsFor("nope").keySet()).containsExactlyInAnyOrder("animation_type", "first", "last", "duration");
            assertThat(animate().paramsFor(null).keySet()).containsExactlyInAnyOrder("animation_type", "first", "last", "duration");
            assertThat(animate().hasAnimation("fire")).isTrue();
            assertThat(animate().hasAnimation("disco")).isFalse();
            assertThat(animate().hasAnimation(null)).isFalse();
        }

        @Test
        @DisplayName("fade exposes waypoints as a colour list")
        void should_exposeFadeWaypoints() {
            CatalogAnimation fade = animate().animations().get("fade");
            assertThat(fade.unsupported()).isEmpty();
            assertThat(fade.params().get("waypoints").type()).isEqualTo(CatalogType.RGB_LIST);
            assertThat(fade.params().get("waypoints").required()).isFalse();
        }
    }

    @Nested
    @DisplayName("duration parameter")
    class DurationParam {

        @ParameterizedTest
        @CsvSource({"RELAY,turn_on,time", "RELAY,turn_off,time", "RELAY,pulse,duration", "LED_SEGMENT,animate,duration",
                "MOTOR,forward,time", "MOTOR,reverse,time", "AUDIO,play,time", "MOVING_HEAD,set,time", "MOVING_HEAD,blackout,time"})
        @DisplayName("names the parameter that receives the clip length")
        void should_nameDurationParam(OutputKind kind, String action, String expected) {
            assertThat(catalog.action(kind, action).orElseThrow().durationParam()).contains(expected);
        }

        @ParameterizedTest
        @CsvSource({"LED_SEGMENT,fill", "LED_SEGMENT,clear", "MOTOR,stop", "AUDIO,stop", "LED_STRIP,setBrightness"})
        @DisplayName("is empty for actions without one")
        void should_beEmptyWithoutDuration(OutputKind kind, String action) {
            assertThat(catalog.action(kind, action).orElseThrow().durationParam()).isEmpty();
        }
    }

    @Nested
    @DisplayName("board")
    class Board {

        @Test
        @DisplayName("describes rpi4b pins, reserved pins, WS281x channels and DMA rules")
        void should_describeRpi4b() {
            BoardSpec board = catalog.board(OutputCatalog.DEFAULT_BOARD).orElseThrow();
            assertThat(board.id()).isEqualTo("rpi4b");
            assertThat(board.gpioMin()).isZero();
            assertThat(board.gpioMax()).isEqualTo(27);
            assertThat(board.isValidGpio(27)).isTrue();
            assertThat(board.isValidGpio(28)).isFalse();
            assertThat(board.isValidGpio(-1)).isFalse();
            assertThat(board.isReserved(0)).isTrue();
            assertThat(board.isReserved(1)).isTrue();
            assertThat(board.isReserved(2)).isFalse();
            assertThat(board.warnings()).containsKey(2).containsKey(8);
            assertThat(board.ws281xChannel()).containsEntry(13, 1).containsEntry(10, 0).containsEntry(21, 0);
            assertThat(board.ws281xChannel()).doesNotContainKey(17);
            assertThat(board.dmaMax()).isEqualTo(14);
            assertThat(board.dmaAvoid()).containsExactlyInAnyOrder(2, 3, 6, 7);
        }

        @Test
        @DisplayName("returns empty for an unknown board")
        void should_returnEmptyForUnknownBoard() {
            assertThat(catalog.board("esp32-devkit")).isEmpty();
        }
    }

    @Nested
    @DisplayName("CatalogType compatibility")
    class TypeCompatibility {

        @ParameterizedTest
        @CsvSource({
                "INT,INTEGER,true", "INT,DECIMAL,false", "INT,TEXT,false",
                "NUMBER,DECIMAL,true", "NUMBER,INTEGER,true", "NUMBER,PRICE,false", "NUMBER,TEXT,false",
                "BOOL,BOOLEAN,true", "BOOL,TEXT,false",
                "STRING,TEXT,true", "STRING,LONGTEXT,false", "STRING,URL,false",
                "RGB,TEXT,true", "RGB,INTEGER,false",
                "RGB_LIST,TEXT,true", "RGB_LIST,LONGTEXT,true", "RGB_LIST,INTEGER,false", "RGB_LIST,MULTI_SELECT,false",
                "ENUM,SELECT,true", "ENUM,MULTI_SELECT,false", "ENUM,TEXT,false"})
        @DisplayName("accepts only the attribute types of the mapping table")
        void should_acceptMappedTypes(CatalogType catalogType, AttributeType attributeType, boolean expected) {
            assertThat(catalogType.accepts(attributeType)).isEqualTo(expected);
        }

        @Test
        @DisplayName("rejects a null attribute type and parses lowercase ids")
        void should_handleNullAndParse() {
            assertThat(CatalogType.INT.accepts(null)).isFalse();
            assertThat(CatalogType.fromCatalogId("rgb")).isEqualTo(CatalogType.RGB);
            assertThat(CatalogType.fromCatalogId("rgb_list")).isEqualTo(CatalogType.RGB_LIST);
            assertThat(CatalogType.RGB_LIST.acceptedAttributeTypes()).isEqualTo("TEXT, LONGTEXT");
            assertThat(CatalogType.NUMBER.acceptedAttributeTypes()).isEqualTo("DECIMAL, INTEGER");
            assertThat(CatalogType.ENUM.acceptedAttributeTypes()).isEqualTo("SELECT");
        }
    }
}
