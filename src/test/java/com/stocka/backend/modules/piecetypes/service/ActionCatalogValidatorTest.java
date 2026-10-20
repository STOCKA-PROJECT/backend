package com.stocka.backend.modules.piecetypes.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

import com.stocka.backend.modules.common.error.ApiException;
import com.stocka.backend.modules.common.error.ErrorCodes;
import com.stocka.backend.modules.contract.OutputCatalog;
import com.stocka.backend.modules.contract.OutputKind;
import com.stocka.backend.modules.piecetypes.dto.ActionParameterDto;
import com.stocka.backend.modules.piecetypes.dto.AttributeValidatorsDto;
import com.stocka.backend.modules.piecetypes.entity.AttributeType;

@DisplayName("ActionCatalogValidator")
class ActionCatalogValidatorTest {

    private static ActionCatalogValidator sut;

    @BeforeAll
    static void setUp() {
        sut = new ActionCatalogValidator(new OutputCatalog());
    }

    private static ActionParameterDto dynamic(String name, AttributeType type) {
        return new ActionParameterDto().setName(name).setType(type).setRequired(true).setDynamic(true);
    }

    private static ActionParameterDto fixed(String name, AttributeType type, String value) {
        return new ActionParameterDto().setName(name).setType(type).setRequired(true).setDynamic(false).setStaticValue(value);
    }

    private static ActionParameterDto select(String name, boolean dynamic, String staticValue, String... options) {
        return new ActionParameterDto().setName(name).setType(AttributeType.SELECT).setRequired(true)
                .setDynamic(dynamic).setStaticValue(staticValue)
                .setValidators(new AttributeValidatorsDto().setOptions(List.of(options)));
    }

    private static void assertCode(Runnable call, String code) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.getCode()).isEqualTo(code);
                });
    }

    @Nested
    @DisplayName("without a kind")
    class NoKind {

        @Test
        @DisplayName("accepts anything")
        void should_skipValidation() {
            assertThatCode(() -> sut.validate(null, "lo_que_sea", List.of(dynamic("x", AttributeType.TEXT)))).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("action name")
    class ActionName {

        @Test
        @DisplayName("accepts an action of the kind with no parameters when none are required")
        void should_acceptKnownAction() {
            assertThatCode(() -> sut.validate(OutputKind.RELAY, "turn_on", List.of())).doesNotThrowAnyException();
            assertThatCode(() -> sut.validate(OutputKind.MOTOR, "stop", List.of())).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("rejects an action of another kind with piece_types.action_not_in_catalog")
        void should_rejectActionOfOtherKind() {
            assertThatThrownBy(() -> sut.validate(OutputKind.RELAY, "fill", List.of()))
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.getCode()).isEqualTo(ErrorCodes.PIECE_TYPES_ACTION_NOT_IN_CATALOG);
                        assertThat(ex.getParams()).containsEntry("action", "fill").containsEntry("kind", "relay");
                    });
        }

        @Test
        @DisplayName("rejects an unknown or null action name")
        void should_rejectUnknownAction() {
            assertCode(() -> sut.validate(OutputKind.RELAY, "encender", List.of()), ErrorCodes.PIECE_TYPES_ACTION_NOT_IN_CATALOG);
            assertCode(() -> sut.validate(OutputKind.RELAY, null, List.of()), ErrorCodes.PIECE_TYPES_ACTION_NOT_IN_CATALOG);
        }

        @Test
        @DisplayName("the button has no actions at all")
        void should_rejectAnyButtonAction() {
            assertCode(() -> sut.validate(OutputKind.BUTTON, "press", List.of()), ErrorCodes.PIECE_TYPES_ACTION_NOT_IN_CATALOG);
        }
    }

    @Nested
    @DisplayName("parameter names")
    class ParamNames {

        @Test
        @DisplayName("accepts catalog parameters")
        void should_acceptCatalogParams() {
            assertThatCode(() -> sut.validate(OutputKind.RELAY, "turn_on", List.of(dynamic("time", AttributeType.DECIMAL))))
                    .doesNotThrowAnyException();
            assertThatCode(() -> sut.validate(OutputKind.LED_SEGMENT, "fill", List.of(dynamic("color", AttributeType.TEXT))))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("rejects a parameter the action does not have with piece_types.action_param_unknown")
        void should_rejectUnknownParam() {
            assertThatThrownBy(() -> sut.validate(OutputKind.RELAY, "turn_on", List.of(dynamic("volume", AttributeType.INTEGER))))
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.getCode()).isEqualTo(ErrorCodes.PIECE_TYPES_ACTION_PARAM_UNKNOWN);
                        assertThat(ex.getParams()).containsEntry("param", "volume").containsEntry("action", "turn_on");
                    });
            assertCode(() -> sut.validate(OutputKind.LED_SEGMENT, "fill",
                    List.of(dynamic("color", AttributeType.TEXT), dynamic("speed", AttributeType.DECIMAL))),
                    ErrorCodes.PIECE_TYPES_ACTION_PARAM_UNKNOWN);
        }
    }

    @Nested
    @DisplayName("required parameters")
    class RequiredParams {

        @Test
        @DisplayName("requires the catalog's mandatory parameters")
        void should_requireMandatory() {
            assertThatThrownBy(() -> sut.validate(OutputKind.LED_SEGMENT, "fill", List.of()))
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.getCode()).isEqualTo(ErrorCodes.PIECE_TYPES_ACTION_PARAM_MISSING);
                        assertThat(ex.getParams()).containsEntry("param", "color");
                    });
            assertCode(() -> sut.validate(OutputKind.LED_SEGMENT, "animate", List.of()), ErrorCodes.PIECE_TYPES_ACTION_PARAM_MISSING);
            assertCode(() -> sut.validate(OutputKind.AUDIO, "play", List.of(dynamic("time", AttributeType.DECIMAL))),
                    ErrorCodes.PIECE_TYPES_ACTION_PARAM_MISSING);
        }

        @Test
        @DisplayName("with a static animation_type, requires that animation's mandatory parameters")
        void should_requireAnimationParamsWhenStatic() {
            assertThatThrownBy(() -> sut.validate(OutputKind.LED_SEGMENT, "animate",
                    List.of(select("animation_type", false, "fade", "fade"))))
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.getCode()).isEqualTo(ErrorCodes.PIECE_TYPES_ACTION_PARAM_MISSING);
                        assertThat(ex.getParams()).containsEntry("param", "color");
                    });
            assertThatCode(() -> sut.validate(OutputKind.LED_SEGMENT, "animate",
                    List.of(select("animation_type", false, "fade", "fade"), dynamic("color", AttributeType.TEXT))))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("with a dynamic animation_type, animation parameters are optional and any of them is allowed")
        void should_relaxWhenDynamic() {
            assertThatCode(() -> sut.validate(OutputKind.LED_SEGMENT, "animate",
                    List.of(select("animation_type", true, null, "fade", "fire"), dynamic("intensity", AttributeType.DECIMAL))))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("with a static animation_type, parameters of other animations are unknown")
        void should_restrictToStaticAnimation() {
            assertThatThrownBy(() -> sut.validate(OutputKind.LED_SEGMENT, "animate",
                    List.of(select("animation_type", false, "fade", "fade"), dynamic("color", AttributeType.TEXT),
                            dynamic("intensity", AttributeType.DECIMAL))))
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.getCode()).isEqualTo(ErrorCodes.PIECE_TYPES_ACTION_PARAM_UNKNOWN);
                        assertThat(ex.getParams()).containsEntry("param", "intensity");
                    });
        }

        @Test
        @DisplayName("a static animation_type that is not a known animation is a static-value error, not a missing one")
        void should_rejectUnknownStaticAnimation() {
            assertCode(() -> sut.validate(OutputKind.LED_SEGMENT, "animate",
                    List.of(select("animation_type", false, "disco", "fade"))), ErrorCodes.PIECE_TYPES_ACTION_PARAM_STATIC_INVALID);
        }
    }

    @Nested
    @DisplayName("types")
    class Types {

        @Test
        @DisplayName("number accepts INTEGER and DECIMAL but not TEXT")
        void should_checkNumber() {
            assertThatCode(() -> sut.validate(OutputKind.RELAY, "turn_on", List.of(dynamic("time", AttributeType.INTEGER)))).doesNotThrowAnyException();
            assertThatThrownBy(() -> sut.validate(OutputKind.RELAY, "turn_on", List.of(dynamic("time", AttributeType.TEXT))))
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.getCode()).isEqualTo(ErrorCodes.PIECE_TYPES_ACTION_PARAM_TYPE_MISMATCH);
                        assertThat(ex.getParams()).containsEntry("param", "time").containsEntry("expected", "DECIMAL, INTEGER");
                    });
        }

        @Test
        @DisplayName("int accepts only INTEGER")
        void should_checkInt() {
            assertCode(() -> sut.validate(OutputKind.LED_STRIP, "setBrightness", List.of(dynamic("brightness", AttributeType.DECIMAL))),
                    ErrorCodes.PIECE_TYPES_ACTION_PARAM_TYPE_MISMATCH);
            assertThatCode(() -> sut.validate(OutputKind.LED_STRIP, "setBrightness", List.of(dynamic("brightness", AttributeType.INTEGER))))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("rgb and string accept TEXT only; enum accepts SELECT only")
        void should_checkTextAndEnum() {
            assertCode(() -> sut.validate(OutputKind.LED_SEGMENT, "fill", List.of(dynamic("color", AttributeType.INTEGER))),
                    ErrorCodes.PIECE_TYPES_ACTION_PARAM_TYPE_MISMATCH);
            assertCode(() -> sut.validate(OutputKind.AUDIO, "play", List.of(dynamic("audio_name", AttributeType.INTEGER))),
                    ErrorCodes.PIECE_TYPES_ACTION_PARAM_TYPE_MISMATCH);
            assertCode(() -> sut.validate(OutputKind.LED_SEGMENT, "animate", List.of(dynamic("animation_type", AttributeType.TEXT))),
                    ErrorCodes.PIECE_TYPES_ACTION_PARAM_TYPE_MISMATCH);
        }
    }

    @Nested
    @DisplayName("enum options")
    class EnumOptions {

        @Test
        @DisplayName("accepts a subset of the catalog values")
        void should_acceptSubset() {
            assertThatCode(() -> sut.validate(OutputKind.LED_SEGMENT, "animate",
                    List.of(select("animation_type", true, null, "fade", "fire")))).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("rejects options outside the catalog, missing or empty options")
        void should_rejectBadOptions() {
            assertThatThrownBy(() -> sut.validate(OutputKind.LED_SEGMENT, "animate",
                    List.of(select("animation_type", true, null, "fade", "disco"))))
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.getCode()).isEqualTo(ErrorCodes.PIECE_TYPES_ACTION_PARAM_OPTIONS_INVALID);
                        assertThat(ex.getParams()).containsEntry("param", "animation_type");
                    });
            ActionParameterDto noValidators = new ActionParameterDto().setName("animation_type").setType(AttributeType.SELECT)
                    .setRequired(true).setDynamic(true);
            assertCode(() -> sut.validate(OutputKind.LED_SEGMENT, "animate", List.of(noValidators)),
                    ErrorCodes.PIECE_TYPES_ACTION_PARAM_OPTIONS_INVALID);
            assertCode(() -> sut.validate(OutputKind.LED_SEGMENT, "animate", List.of(select("animation_type", true, null))),
                    ErrorCodes.PIECE_TYPES_ACTION_PARAM_OPTIONS_INVALID);
        }
    }

    @Nested
    @DisplayName("duration")
    class Duration {

        @Test
        @DisplayName("accepts isDuration on the catalog's duration parameter")
        void should_acceptDurationParam() {
            assertThatCode(() -> sut.validate(OutputKind.RELAY, "turn_on",
                    List.of(dynamic("time", AttributeType.DECIMAL).setIsDuration(true)))).doesNotThrowAnyException();
            assertThatCode(() -> sut.validate(OutputKind.LED_SEGMENT, "animate",
                    List.of(select("animation_type", true, null, "fade"), dynamic("duration", AttributeType.DECIMAL).setIsDuration(true))))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("rejects isDuration on another parameter, naming the expected one")
        void should_rejectWrongDurationParam() {
            assertThatThrownBy(() -> sut.validate(OutputKind.LED_SEGMENT, "animate",
                    List.of(select("animation_type", true, null, "fade"), dynamic("first", AttributeType.INTEGER).setIsDuration(true))))
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.getCode()).isEqualTo(ErrorCodes.PIECE_TYPES_ACTION_DURATION_PARAM_MISMATCH);
                        assertThat(ex.getParams()).containsEntry("param", "first").containsEntry("expected", "duration");
                    });
        }

        @Test
        @DisplayName("rejects isDuration on an action without a duration parameter")
        void should_rejectDurationOnActionWithoutOne() {
            assertThatThrownBy(() -> sut.validate(OutputKind.LED_SEGMENT, "fill",
                    List.of(dynamic("first", AttributeType.INTEGER).setIsDuration(true))))
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.getCode()).isEqualTo(ErrorCodes.PIECE_TYPES_ACTION_DURATION_PARAM_MISMATCH);
                        assertThat(ex.getParams()).containsEntry("expected", "-");
                    });
        }
    }

    @Nested
    @DisplayName("static values")
    class StaticValues {

        @Nested
        @DisplayName("colour lists (fade waypoints)")
        class ColourLists {

            private List<ActionParameterDto> fadeWith(ActionParameterDto waypoints) {
                return List.of(select("animation_type", false, "fade", "fade"), dynamic("color", AttributeType.TEXT), waypoints);
            }

            @ParameterizedTest
            @ValueSource(strings = {"[[255,0,0],[0,255,0]]", "[\"#ff0000\", \"0,255,0\", [1, 2, 3]]", "[ [1,2,3] ]", "[]"})
            @DisplayName("accepts a JSON array of colours in any supported notation, even empty")
            void should_acceptColourLists(String value) {
                assertThatCode(() -> sut.validate(OutputKind.LED_SEGMENT, "animate", fadeWith(fixed("waypoints", AttributeType.TEXT, value))))
                        .doesNotThrowAnyException();
            }

            @ParameterizedTest
            @ValueSource(strings = {"#ff0000", "[255,0,0]", "[[255,0,300]]", "[[1,2]]", "[\"blau\"]", "[1,2,3]", "{}", "no json"})
            @DisplayName("rejects values that are not a list of colours")
            void should_rejectNonLists(String value) {
                assertCode(() -> sut.validate(OutputKind.LED_SEGMENT, "animate", fadeWith(fixed("waypoints", AttributeType.TEXT, value))),
                        ErrorCodes.PIECE_TYPES_ACTION_PARAM_STATIC_INVALID);
            }

            @Test
            @DisplayName("a dynamic colour list may be TEXT or LONGTEXT but not a number")
            void should_checkAttributeType() {
                assertThatCode(() -> sut.validate(OutputKind.LED_SEGMENT, "animate", fadeWith(dynamic("waypoints", AttributeType.TEXT)))).doesNotThrowAnyException();
                assertThatCode(() -> sut.validate(OutputKind.LED_SEGMENT, "animate", fadeWith(dynamic("waypoints", AttributeType.LONGTEXT)))).doesNotThrowAnyException();
                assertCode(() -> sut.validate(OutputKind.LED_SEGMENT, "animate", fadeWith(dynamic("waypoints", AttributeType.INTEGER))),
                        ErrorCodes.PIECE_TYPES_ACTION_PARAM_TYPE_MISMATCH);
            }

            @Test
            @DisplayName("waypoints belong to fade only")
            void should_rejectWaypointsOnOtherAnimations() {
                assertCode(() -> sut.validate(OutputKind.LED_SEGMENT, "animate",
                        List.of(select("animation_type", false, "fire", "fire"), dynamic("waypoints", AttributeType.TEXT))),
                        ErrorCodes.PIECE_TYPES_ACTION_PARAM_UNKNOWN);
            }
        }

        @ParameterizedTest
        @ValueSource(strings = {"[255,50,0]", "[255, 50, 0]", "255,50,0", "255, 50 , 0", "#ff3200", "#F30", "#fff"})
        @DisplayName("accepts every supported colour notation")
        void should_acceptRgbForms(String value) {
            assertThatCode(() -> sut.validate(OutputKind.LED_SEGMENT, "fill", List.of(fixed("color", AttributeType.TEXT, value))))
                    .doesNotThrowAnyException();
        }

        @ParameterizedTest
        @ValueSource(strings = {"rojo", "[256,0,0]", "[1,2]", "#ggg", "#12345", "1,2,3,4", ""})
        @DisplayName("rejects text that is not a colour with piece_types.action_param_static_invalid")
        void should_rejectNonRgb(String value) {
            assertCode(() -> sut.validate(OutputKind.LED_SEGMENT, "fill", List.of(fixed("color", AttributeType.TEXT, value))),
                    ErrorCodes.PIECE_TYPES_ACTION_PARAM_STATIC_INVALID);
        }

        @Test
        @DisplayName("checks numbers against their bounds and integers against integrality")
        void should_checkNumbers() {
            assertThatCode(() -> sut.validate(OutputKind.RELAY, "turn_on", List.of(fixed("time", AttributeType.DECIMAL, "2.5"))))
                    .doesNotThrowAnyException();
            assertCode(() -> sut.validate(OutputKind.RELAY, "turn_on", List.of(fixed("time", AttributeType.DECIMAL, "-1"))),
                    ErrorCodes.PIECE_TYPES_ACTION_PARAM_STATIC_INVALID);
            assertCode(() -> sut.validate(OutputKind.RELAY, "turn_on", List.of(fixed("time", AttributeType.DECIMAL, "abc"))),
                    ErrorCodes.PIECE_TYPES_ACTION_PARAM_STATIC_INVALID);
            assertThatCode(() -> sut.validate(OutputKind.LED_STRIP, "setBrightness", List.of(fixed("brightness", AttributeType.INTEGER, "12"))))
                    .doesNotThrowAnyException();
            assertCode(() -> sut.validate(OutputKind.LED_STRIP, "setBrightness", List.of(fixed("brightness", AttributeType.INTEGER, "300"))),
                    ErrorCodes.PIECE_TYPES_ACTION_PARAM_STATIC_INVALID);
            assertCode(() -> sut.validate(OutputKind.LED_STRIP, "setBrightness", List.of(fixed("brightness", AttributeType.INTEGER, "12.5"))),
                    ErrorCodes.PIECE_TYPES_ACTION_PARAM_STATIC_INVALID);
        }

        @Test
        @DisplayName("checks enum, boolean and string static values")
        void should_checkOtherTypes() {
            assertThatCode(() -> sut.validate(OutputKind.LED_SEGMENT, "animate",
                    List.of(select("animation_type", false, "fire", "fire")))).doesNotThrowAnyException();
            assertCode(() -> sut.validate(OutputKind.LED_SEGMENT, "animate",
                    List.of(select("animation_type", false, "disco", "fire"))), ErrorCodes.PIECE_TYPES_ACTION_PARAM_STATIC_INVALID);
            assertThatCode(() -> sut.validate(OutputKind.AUDIO, "play", List.of(fixed("audio_name", AttributeType.TEXT, "nature.mp3"))))
                    .doesNotThrowAnyException();
            assertCode(() -> sut.validate(OutputKind.AUDIO, "play", List.of(fixed("audio_name", AttributeType.TEXT, "   "))),
                    ErrorCodes.PIECE_TYPES_ACTION_PARAM_STATIC_INVALID);
        }

        @Test
        @DisplayName("ignores static values of dynamic parameters")
        void should_ignoreDynamicStaticValue() {
            ActionParameterDto p = dynamic("color", AttributeType.TEXT).setStaticValue("rojo");
            assertThatCode(() -> sut.validate(OutputKind.LED_SEGMENT, "fill", List.of(p))).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("parseRgb returns the components or null")
        void should_parseRgb() {
            assertThat(ActionCatalogValidator.parseRgb("#ff3200")).containsExactly(255, 50, 0);
            assertThat(ActionCatalogValidator.parseRgb("#f30")).containsExactly(255, 51, 0);
            assertThat(ActionCatalogValidator.parseRgb("[10, 0, 60]")).containsExactly(10, 0, 60);
            assertThat(ActionCatalogValidator.parseRgb("10,0,60")).containsExactly(10, 0, 60);
            assertThat(ActionCatalogValidator.parseRgb("300,0,0")).isNull();
            assertThat(ActionCatalogValidator.parseRgb("azul")).isNull();
        }
    }
}
