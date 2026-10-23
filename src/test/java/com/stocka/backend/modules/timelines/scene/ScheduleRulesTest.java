package com.stocka.backend.modules.timelines.scene;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.stocka.backend.modules.timelines.scene.SceneDocument.Schedule;
import com.stocka.backend.modules.timelines.scene.SceneDocument.ScheduleWindow;

@DisplayName("ScheduleRules")
class ScheduleRulesTest {

    private static Schedule allow(ScheduleWindow... windows) {
        return new Schedule("allow", Arrays.asList(windows));
    }

    private static ScheduleWindow window(String from, String to, String... days) {
        return new ScheduleWindow(Arrays.asList(days), from, to);
    }

    @Nested
    @DisplayName("valid schedules")
    class Valid {

        @Test
        @DisplayName("allow and block with ordinary windows have no problems")
        void ordinary() {
            assertThat(ScheduleRules.problems(allow(window("17:00", "21:00", "mon", "fri")))).isEmpty();
            assertThat(ScheduleRules.problems(new Schedule("block", List.of(window("13:00", "16:00", "sun"))))).isEmpty();
        }

        @ParameterizedTest(name = "{0}–{1}")
        @CsvSource({"22:00,02:00", "00:00,24:00", "22:00,00:00", "23:59,24:00"})
        @DisplayName("overnight, whole-day and until-midnight windows are valid")
        void edges(String from, String to) {
            assertThat(ScheduleRules.problems(allow(window(from, to, "sat")))).isEmpty();
        }

        @Test
        @DisplayName("fifty windows are allowed")
        void fiftyWindows() {
            List<ScheduleWindow> windows = new ArrayList<>();
            for (int i = 0; i < ScheduleRules.MAX_WINDOWS; i++) windows.add(window("10:00", "11:00", "mon"));
            assertThat(ScheduleRules.problems(new Schedule("allow", windows))).isEmpty();
        }
    }

    @Nested
    @DisplayName("structural problems")
    class Structure {

        @ParameterizedTest
        @ValueSource(strings = {"sometimes", "ALLOW", ""})
        @DisplayName("an unknown rule is reported")
        void rule(String rule) {
            assertThat(ScheduleRules.problems(new Schedule(rule, List.of(window("10:00", "11:00", "mon")))))
                    .containsExactly("rule must be allow or block");
        }

        @Test
        @DisplayName("a null rule is reported")
        void nullRule() {
            assertThat(ScheduleRules.problems(new Schedule(null, List.of(window("10:00", "11:00", "mon")))))
                    .containsExactly("rule must be allow or block");
        }

        @Test
        @DisplayName("no windows (empty or null) is reported")
        void noWindows() {
            assertThat(ScheduleRules.problems(new Schedule("allow", List.of()))).containsExactly("at least one window is needed");
            assertThat(ScheduleRules.problems(new Schedule("allow", null))).containsExactly("at least one window is needed");
        }

        @Test
        @DisplayName("more than fifty windows is reported")
        void tooManyWindows() {
            List<ScheduleWindow> windows = new ArrayList<>();
            for (int i = 0; i <= ScheduleRules.MAX_WINDOWS; i++) windows.add(window("10:00", "11:00", "mon"));
            assertThat(ScheduleRules.problems(new Schedule("allow", windows))).containsExactly("at most 50 windows");
        }

        @Test
        @DisplayName("a null window is reported")
        void nullWindow() {
            assertThat(ScheduleRules.problems(new Schedule("allow", Arrays.asList((ScheduleWindow) null))))
                    .containsExactly("window 1 is empty");
        }

        @Test
        @DisplayName("days must be present, known and not repeated")
        void days() {
            assertThat(ScheduleRules.problems(allow(window("10:00", "11:00")))).containsExactly("window 1 needs at least one day");
            assertThat(ScheduleRules.problems(allow(new ScheduleWindow(null, "10:00", "11:00"))))
                    .containsExactly("window 1 needs at least one day");
            assertThat(ScheduleRules.problems(allow(window("10:00", "11:00", "lunes")))).singleElement()
                    .asString().contains("unknown day \"lunes\"");
            assertThat(ScheduleRules.problems(allow(window("10:00", "11:00", "mon", "mon"))))
                    .containsExactly("window 1 repeats mon");
            assertThat(ScheduleRules.problems(allow(new ScheduleWindow(Arrays.asList("mon", null), "10:00", "11:00"))))
                    .singleElement().asString().contains("unknown day \"null\"");
        }

        @ParameterizedTest
        @ValueSource(strings = {"24:00", "7:00", "10:60", "25:00", "1000", ""})
        @DisplayName("from must be HH:MM up to 23:59")
        void from(String from) {
            assertThat(ScheduleRules.problems(allow(window(from, "11:30", "mon"))))
                    .contains("window 1: from must be HH:MM (00:00 to 23:59)");
        }

        @ParameterizedTest
        @ValueSource(strings = {"24:01", "24:30", "9:00", "12:5"})
        @DisplayName("to must be HH:MM or 24:00")
        void to(String to) {
            assertThat(ScheduleRules.problems(allow(window("10:00", to, "mon")))).containsExactly("window 1: to must be HH:MM or 24:00");
        }

        @Test
        @DisplayName("missing times are reported")
        void missingTimes() {
            assertThat(ScheduleRules.problems(allow(window(null, null, "mon"))))
                    .containsExactly("window 1: from must be HH:MM (00:00 to 23:59)", "window 1: to must be HH:MM or 24:00");
        }

        @Test
        @DisplayName("problems name the window they come from")
        void numbering() {
            assertThat(ScheduleRules.problems(allow(window("10:00", "11:00", "mon"), window("10:00", "11:00", "xx"))))
                    .singleElement().asString().startsWith("window 2");
        }
    }

    @Nested
    @DisplayName("empty windows")
    class EmptyWindow {

        @Test
        @DisplayName("a window that starts and ends at the same time lasts nothing")
        void sameTime() {
            ScheduleWindow w = window("17:00", "17:00", "mon");
            assertThat(ScheduleRules.isEmpty(w)).isTrue();
            assertThat(ScheduleRules.problems(allow(w))).containsExactly("window 1 starts and ends at 17:00: it lasts nothing");
            assertThat(ScheduleRules.structuralProblems(allow(w))).isEmpty();
        }

        @Test
        @DisplayName("00:00–24:00 is not empty")
        void wholeDay() {
            assertThat(ScheduleRules.isEmpty(window("00:00", "24:00", "mon"))).isFalse();
        }
    }
}
