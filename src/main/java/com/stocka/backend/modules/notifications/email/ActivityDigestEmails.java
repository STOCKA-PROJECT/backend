package com.stocka.backend.modules.notifications.email;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.stocka.backend.modules.notifications.events.ResourceKind;
import com.stocka.backend.modules.notifications.preferences.entity.LifecycleAction;

/**
 * Builds the Thymeleaf model of the {@code activity-digest} template so the three
 * {@link EmailService} implementations render exactly the same email. Items are grouped into
 * sections by kind and action in a fixed order (pieces, locations, piece types; created, edited,
 * deleted) and every section carries its i18n title key and its count.
 *
 * @since 2026-10-29
 */
public final class ActivityDigestEmails {

    /** Template name under {@code templates/email/}. */
    public static final String TEMPLATE = "activity-digest";
    /** Subject key; arguments are the organization name and the number of changes. */
    public static final String SUBJECT_KEY = "email.activityDigest.subject";

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("d MMM HH:mm");

    private ActivityDigestEmails() {
    }

    /**
     * A group of digest lines sharing kind and action.
     *
     * @param titleKey i18n key of the section title
     * @param count    number of lines
     * @param items    the lines, oldest first
     */
    public record Section(String titleKey, int count, List<Line> items) {
    }

    /**
     * One rendered line.
     *
     * @param name      resource name
     * @param url       link, or {@code null} when the resource no longer exists
     * @param actorName who did it
     * @param time      formatted time in the digest zone
     */
    public record Line(String name, String url, String actorName, String time) {
    }

    /**
     * Subject arguments: organization name and total number of changes.
     *
     * @param orgName organization display name
     * @param items   digest lines
     * @return arguments for {@link #SUBJECT_KEY}
     */
    public static Object[] subjectArgs(String orgName, List<ActivityDigestItem> items) {
        return new Object[]{orgName, items == null ? 0 : items.size()};
    }

    /**
     * Template variables for one digest.
     *
     * @param orgName organization display name
     * @param orgUrl  absolute URL of the organization dashboard (CTA)
     * @param items   digest lines, any order, with times already in the zone to print
     * @param locale  recipient locale, used for month names
     * @return variables consumed by {@code activity-digest.html}
     */
    public static Map<String, Object> variables(
            String orgName, String orgUrl, List<ActivityDigestItem> items, Locale locale
    ) {
        List<ActivityDigestItem> sorted = items == null ? List.of() : items.stream()
                .sorted(Comparator.comparing(ActivityDigestItem::eventAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        DateTimeFormatter formatter = TIME.withLocale(locale == null ? Locale.getDefault() : locale);

        Map<String, List<Line>> grouped = new LinkedHashMap<>();
        for (ResourceKind kind : ResourceKind.values()) {
            for (LifecycleAction action : LifecycleAction.values()) {
                grouped.put(kind.name() + "." + action.name(), new ArrayList<>());
            }
        }
        for (ActivityDigestItem item : sorted) {
            if (item.kind() == null || item.action() == null) continue;
            String url = item.action() == LifecycleAction.DELETED ? null : item.resourceUrl();
            grouped.get(item.kind().name() + "." + item.action().name()).add(new Line(
                    item.resourceName() == null ? "" : item.resourceName(),
                    url,
                    item.actorName() == null ? "" : item.actorName(),
                    format(item.eventAt(), formatter)));
        }

        List<Section> sections = new ArrayList<>();
        grouped.forEach((suffix, lines) -> {
            if (!lines.isEmpty()) {
                sections.add(new Section("email.activityDigest.section." + suffix, lines.size(), lines));
            }
        });

        String from = sorted.isEmpty() ? "" : format(sorted.getFirst().eventAt(), formatter);
        String to = sorted.isEmpty() ? "" : format(sorted.getLast().eventAt(), formatter);

        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("orgName", orgName == null ? "" : orgName);
        vars.put("orgUrl", orgUrl == null ? "" : orgUrl);
        vars.put("total", sorted.size());
        vars.put("periodFrom", from);
        vars.put("periodTo", to);
        vars.put("sections", sections);
        return vars;
    }

    private static String format(LocalDateTime at, DateTimeFormatter formatter) {
        return at == null ? "" : at.format(formatter);
    }
}
