package com.stocka.backend.modules.timelines.scene;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import com.stocka.backend.modules.timelines.scene.SceneDocument.Schedule;
import com.stocka.backend.modules.timelines.scene.SceneDocument.ScheduleWindow;

/**
 * Structural rules of a button {@link Schedule}, the same ones {@code rpi4-system/contract/show.schema.json}
 * enforces on {@code mode.schedule} plus "a window must last something". Shared by {@link SceneEditor}
 * ({@code set_schedule}), {@link SceneValidator} and the show validator.
 */
public final class ScheduleRules {

    /** Days of the week, Monday first. */
    public static final List<String> DAYS = List.of("mon", "tue", "wed", "thu", "fri", "sat", "sun");
    /** Accepted rules. */
    public static final List<String> RULES = List.of("allow", "block");
    /** Maximum number of windows. */
    public static final int MAX_WINDOWS = 50;

    private static final Pattern FROM = Pattern.compile("^([01][0-9]|2[0-3]):[0-5][0-9]$");
    private static final Pattern TO = Pattern.compile("^(([01][0-9]|2[0-3]):[0-5][0-9]|24:00)$");

    private ScheduleRules() {
    }

    /**
     * @param schedule schedule to check, not {@code null}
     * @return what is wrong with it, in English, empty when valid
     */
    public static List<String> problems(Schedule schedule) {
        List<String> problems = structuralProblems(schedule);
        List<ScheduleWindow> windows = schedule.windows() == null ? List.of() : schedule.windows();
        for (int i = 0; i < windows.size(); i++) {
            if (windows.get(i) != null && isEmpty(windows.get(i))) {
                problems.add("window " + (i + 1) + " starts and ends at " + windows.get(i).from() + ": it lasts nothing");
            }
        }
        return problems;
    }

    /**
     * What the device's JSON schema checks: rule, number of windows, days and time formats.
     *
     * @param schedule schedule to check, not {@code null}
     * @return what is wrong with its structure, in English, empty when well formed
     */
    public static List<String> structuralProblems(Schedule schedule) {
        List<String> problems = new ArrayList<>();
        if (schedule.rule() == null || !RULES.contains(schedule.rule())) {
            problems.add("rule must be allow or block");
        }
        List<ScheduleWindow> windows = schedule.windows() == null ? List.of() : schedule.windows();
        if (windows.isEmpty()) {
            problems.add("at least one window is needed");
        } else if (windows.size() > MAX_WINDOWS) {
            problems.add("at most " + MAX_WINDOWS + " windows");
        }
        for (int i = 0; i < windows.size(); i++) {
            problems.addAll(windowProblems(i + 1, windows.get(i)));
        }
        return problems;
    }

    /**
     * @param window a window
     * @return {@code true} when it starts and ends at the same time (it lasts nothing)
     */
    public static boolean isEmpty(ScheduleWindow window) {
        return window.from() != null && window.from().equals(window.to());
    }

    private static List<String> windowProblems(int n, ScheduleWindow w) {
        List<String> problems = new ArrayList<>();
        if (w == null) {
            problems.add("window " + n + " is empty");
            return problems;
        }
        List<String> days = w.days() == null ? List.of() : w.days();
        if (days.isEmpty()) {
            problems.add("window " + n + " needs at least one day");
        }
        Set<String> seen = new HashSet<>();
        for (String day : days) {
            if (day == null || !DAYS.contains(day)) {
                problems.add("window " + n + ": unknown day \"" + day + "\" (use " + String.join(", ", DAYS) + ")");
            } else if (!seen.add(day)) {
                problems.add("window " + n + " repeats " + day);
            }
        }
        if (w.from() == null || !FROM.matcher(w.from()).matches()) {
            problems.add("window " + n + ": from must be HH:MM (00:00 to 23:59)");
        }
        if (w.to() == null || !TO.matcher(w.to()).matches()) {
            problems.add("window " + n + ": to must be HH:MM or 24:00");
        }
        return problems;
    }
}
