package com.stocka.backend.modules.notifications.email;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.stocka.backend.modules.notifications.email.ActivityDigestEmails.Line;
import com.stocka.backend.modules.notifications.email.ActivityDigestEmails.Section;
import com.stocka.backend.modules.notifications.events.ResourceKind;
import com.stocka.backend.modules.notifications.preferences.entity.LifecycleAction;

@DisplayName("ActivityDigestEmails")
class ActivityDigestEmailsTest {

    private static final Locale ES = Locale.of("es");
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 29, 9, 5);

    private static ActivityDigestItem item(ResourceKind kind, LifecycleAction action, String name, LocalDateTime at) {
        return new ActivityDigestItem(kind, action, name, "https://x/" + name, "Anna", at);
    }

    @SuppressWarnings("unchecked")
    private static List<Section> sections(Map<String, Object> vars) {
        return (List<Section>) vars.get("sections");
    }

    @Nested
    @DisplayName("subjectArgs")
    class SubjectArgs {

        @Test
        @DisplayName("carries the org name and the number of items")
        void should_returnOrgAndCount() {
            assertThat(ActivityDigestEmails.subjectArgs("Betlem", List.of(
                    item(ResourceKind.PIECE, LifecycleAction.CREATED, "a", T0),
                    item(ResourceKind.PIECE, LifecycleAction.CREATED, "b", T0))))
                    .containsExactly("Betlem", 2);
        }

        @Test
        @DisplayName("counts zero for a null list")
        void should_countZero_when_null() {
            assertThat(ActivityDigestEmails.subjectArgs("Betlem", null)).containsExactly("Betlem", 0);
        }
    }

    @Nested
    @DisplayName("variables")
    class Variables {

        @Test
        @DisplayName("groups the items by kind and action in catalog order with counts")
        void should_groupIntoSections() {
            Map<String, Object> vars = ActivityDigestEmails.variables("Betlem", "https://x/org", List.of(
                    item(ResourceKind.LOCATION, LifecycleAction.CREATED, "Caixa 5", T0.plusMinutes(3)),
                    item(ResourceKind.PIECE, LifecycleAction.EDITED, "Pastor", T0.plusMinutes(2)),
                    item(ResourceKind.PIECE, LifecycleAction.CREATED, "Ovella", T0.plusMinutes(1)),
                    item(ResourceKind.PIECE, LifecycleAction.CREATED, "Bou", T0),
                    item(ResourceKind.PIECE_TYPE, LifecycleAction.DELETED, "Llum", T0.plusMinutes(4))
            ), ES);

            List<Section> sections = sections(vars);
            assertThat(sections).extracting(Section::titleKey).containsExactly(
                    "email.activityDigest.section.PIECE.CREATED",
                    "email.activityDigest.section.PIECE.EDITED",
                    "email.activityDigest.section.LOCATION.CREATED",
                    "email.activityDigest.section.PIECE_TYPE.DELETED");
            assertThat(sections.get(0).count()).isEqualTo(2);
            assertThat(sections.get(0).items()).extracting(Line::name).containsExactly("Bou", "Ovella");
            assertThat(vars).containsEntry("orgName", "Betlem")
                    .containsEntry("orgUrl", "https://x/org")
                    .containsEntry("total", 5);
        }

        @Test
        @DisplayName("prints the period from the oldest to the newest item")
        void should_computePeriod() {
            Map<String, Object> vars = ActivityDigestEmails.variables("B", "u", List.of(
                    item(ResourceKind.PIECE, LifecycleAction.CREATED, "b", T0.plusHours(4)),
                    item(ResourceKind.PIECE, LifecycleAction.CREATED, "a", T0)), ES);

            assertThat(vars.get("periodFrom")).isEqualTo("29 oct 09:05");
            assertThat(vars.get("periodTo")).isEqualTo("29 oct 13:05");
        }

        @Test
        @DisplayName("uses the locale for month names")
        void should_localizeMonth() {
            Map<String, Object> vars = ActivityDigestEmails.variables("B", "u", List.of(
                    item(ResourceKind.PIECE, LifecycleAction.CREATED, "a", T0)), Locale.ENGLISH);

            assertThat(vars.get("periodFrom")).isEqualTo("29 Oct 09:05");
        }

        @Test
        @DisplayName("a deleted resource has no link")
        void should_dropLink_when_deleted() {
            Map<String, Object> vars = ActivityDigestEmails.variables("B", "u", List.of(
                    item(ResourceKind.PIECE, LifecycleAction.DELETED, "gone", T0),
                    item(ResourceKind.PIECE, LifecycleAction.CREATED, "new", T0)), ES);

            List<Section> sections = sections(vars);
            assertThat(sections.get(0).items().getFirst().url()).isEqualTo("https://x/new");
            assertThat(sections.get(1).items().getFirst().url()).isNull();
        }

        @Test
        @DisplayName("tolerates null names, actors, times and an empty list")
        void should_tolerateNulls() {
            Map<String, Object> vars = ActivityDigestEmails.variables(null, null, List.of(
                    new ActivityDigestItem(ResourceKind.PIECE, LifecycleAction.CREATED, null, null, null, null)), ES);
            Line line = sections(vars).getFirst().items().getFirst();
            assertThat(line.name()).isEmpty();
            assertThat(line.actorName()).isEmpty();
            assertThat(line.time()).isEmpty();
            assertThat(vars).containsEntry("orgName", "").containsEntry("orgUrl", "");

            Map<String, Object> empty = ActivityDigestEmails.variables("B", "u", List.of(), ES);
            assertThat(sections(empty)).isEmpty();
            assertThat(empty).containsEntry("total", 0).containsEntry("periodFrom", "").containsEntry("periodTo", "");
            assertThat(sections(ActivityDigestEmails.variables("B", "u", null, ES))).isEmpty();
        }

        @Test
        @DisplayName("skips items without kind or action")
        void should_skipIncompleteItems() {
            Map<String, Object> vars = ActivityDigestEmails.variables("B", "u", List.of(
                    new ActivityDigestItem(null, LifecycleAction.CREATED, "x", "u", "a", T0),
                    new ActivityDigestItem(ResourceKind.PIECE, null, "y", "u", "a", T0)), ES);

            assertThat(sections(vars)).isEmpty();
            assertThat(vars).containsEntry("total", 2);
        }
    }
}
