package com.stocka.backend.modules.timelines.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.stocka.backend.modules.common.error.ApiException;
import com.stocka.backend.modules.common.error.ErrorCodes;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.service.OrganizationService;
import com.stocka.backend.modules.timelines.dto.CreateTimelineDto;
import com.stocka.backend.modules.timelines.dto.UpdateTimelineDto;
import com.stocka.backend.modules.timelines.entity.Timeline;
import com.stocka.backend.modules.timelines.repository.TimelineRepository;
import com.stocka.backend.modules.timelines.repository.TimelineSceneRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("TimelineService")
class TimelineServiceTest {

    @Mock TimelineRepository timelineRepository;
    @Mock OrganizationService organizationService;
    @Mock TimelineSceneRepository sceneRepository;

    private TimelineService sut;

    private final Organization org = new Organization();

    @BeforeEach
    void setUp() {
        org.setId(1);
        sut = new TimelineService(timelineRepository, organizationService, sceneRepository);
        // Lenient because the static-helper tests don't go through the service layer.
        lenient().when(organizationService.findById(1)).thenReturn(org);
    }

    private Timeline existing(int id, String name) {
        Timeline t = new Timeline().setOrganization(org).setName(name);
        t.setId(id);
        return t;
    }

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("persists and returns a timeline when the name is unique")
        void should_createWhenNameIsUnique() {
            when(timelineRepository.findByOrganizationAndName(org, "Hito 1")).thenReturn(Optional.empty());
            when(timelineRepository.saveAndFlush(any(Timeline.class))).thenAnswer(inv -> {
                Timeline t = inv.getArgument(0);
                t.setId(5);
                return t;
            });
            CreateTimelineDto dto = new CreateTimelineDto().setName("Hito 1");

            Timeline created = sut.create(1, dto);

            assertThat(created.getId()).isEqualTo(5);
            assertThat(created.getName()).isEqualTo("Hito 1");
            assertThat(created.getOrganization()).isSameAs(org);
        }

        @Test
        @DisplayName("rejects with TIMELINES_NAME_CONFLICT when an active timeline already uses the name")
        void should_rejectDuplicateName_preCheck() {
            when(timelineRepository.findByOrganizationAndName(org, "Hito 1"))
                    .thenReturn(Optional.of(existing(99, "Hito 1")));
            CreateTimelineDto dto = new CreateTimelineDto().setName("Hito 1");

            assertThatThrownBy(() -> sut.create(1, dto))
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(ex.getCode()).isEqualTo(ErrorCodes.TIMELINES_NAME_CONFLICT);
                    });
            verify(timelineRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("translates a DB-level uk_timeline_org_name violation into a 409 ApiException")
        void should_translateDataIntegrityViolation_intoConflict() {
            // Pre-check sees no duplicate (race: another tx commits in between).
            when(timelineRepository.findByOrganizationAndName(org, "Hito 1")).thenReturn(Optional.empty());
            when(timelineRepository.saveAndFlush(any(Timeline.class)))
                    .thenThrow(new DataIntegrityViolationException("Duplicate entry '1-Hito 1'"));
            CreateTimelineDto dto = new CreateTimelineDto().setName("Hito 1");

            assertThatThrownBy(() -> sut.create(1, dto))
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(ex.getCode()).isEqualTo(ErrorCodes.TIMELINES_NAME_CONFLICT);
                        assertThat(ex.getCause()).isInstanceOf(DataIntegrityViolationException.class);
                    });
        }

        @Test
        @DisplayName("trims whitespace before checking uniqueness and persisting")
        void should_trimNameBeforeUniqueCheck() {
            when(timelineRepository.findByOrganizationAndName(org, "Hito 1")).thenReturn(Optional.empty());
            when(timelineRepository.saveAndFlush(any(Timeline.class))).thenAnswer(inv -> inv.getArgument(0));
            CreateTimelineDto dto = new CreateTimelineDto().setName("  Hito 1  ");

            Timeline created = sut.create(1, dto);

            assertThat(created.getName()).isEqualTo("Hito 1");
            verify(timelineRepository).findByOrganizationAndName(org, "Hito 1");
        }

        @Test
        @DisplayName("rejects blank name with HTTP 400 before touching the repository")
        void should_rejectBlankName() {
            CreateTimelineDto dto = new CreateTimelineDto().setName("   ");

            assertThatThrownBy(() -> sut.create(1, dto))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("nombre");
            verify(timelineRepository, never()).findByOrganizationAndName(any(), any());
        }

        @Test
        @DisplayName("rejects null name with HTTP 400")
        void should_rejectNullName() {
            CreateTimelineDto dto = new CreateTimelineDto();

            assertThatThrownBy(() -> sut.create(1, dto))
                    .isInstanceOf(ResponseStatusException.class);
            verify(timelineRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("rejects a name longer than the column length with HTTP 400")
        void should_rejectTooLongName() {
            String tooLong = "x".repeat(TimelineService.MAX_NAME_LENGTH + 1);
            CreateTimelineDto dto = new CreateTimelineDto().setName(tooLong);

            assertThatThrownBy(() -> sut.create(1, dto))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining(String.valueOf(TimelineService.MAX_NAME_LENGTH));
            verify(timelineRepository, never()).findByOrganizationAndName(any(), any());
        }
    }

    @Nested
    @DisplayName("listAll")
    class ListAll {

        @Test
        @DisplayName("returns every timeline of the organization")
        void should_listOrgTimelines() {
            List<Timeline> stored = List.of(existing(1, "A"), existing(2, "B"));
            when(timelineRepository.findByOrganization(org)).thenReturn(stored);

            assertThat(sut.listAll(1)).isEqualTo(stored);
        }
    }

    @Nested
    @DisplayName("findInOrg")
    class FindInOrg {

        @Test
        @DisplayName("returns the timeline when ownership matches")
        void should_returnWhenOrgMatches() {
            Timeline current = existing(7, "Hito");
            when(timelineRepository.findById(7)).thenReturn(Optional.of(current));

            assertThat(sut.findInOrg(1, 7)).isSameAs(current);
        }

        @Test
        @DisplayName("rejects a missing id with TIMELINES_NOT_FOUND")
        void should_rejectMissingId() {
            when(timelineRepository.findById(7)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> sut.findInOrg(1, 7))
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                        assertThat(ex.getCode()).isEqualTo(ErrorCodes.TIMELINES_NOT_FOUND);
                    });
        }

        @Test
        @DisplayName("rejects access to a timeline that belongs to another org with TIMELINES_NOT_FOUND")
        void should_isolateByOrg() {
            Organization other = new Organization();
            other.setId(99);
            Timeline foreign = new Timeline().setOrganization(other).setName("Hito");
            foreign.setId(7);
            when(timelineRepository.findById(7)).thenReturn(Optional.of(foreign));

            assertThatThrownBy(() -> sut.findInOrg(1, 7))
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                        assertThat(ex.getCode()).isEqualTo(ErrorCodes.TIMELINES_NOT_FOUND);
                    });
        }
    }

    @Nested
    @DisplayName("update — rename")
    class UpdateRename {

        @Test
        @DisplayName("renames when target is unique inside the org")
        void should_renameWhenTargetIsUnique() {
            Timeline current = existing(7, "Hito");
            when(timelineRepository.findById(7)).thenReturn(Optional.of(current));
            when(timelineRepository.findByOrganizationAndName(org, "Lanzamiento")).thenReturn(Optional.empty());
            when(timelineRepository.saveAndFlush(current)).thenReturn(current);
            UpdateTimelineDto dto = new UpdateTimelineDto().setName("Lanzamiento");

            Timeline saved = sut.update(1, 7, dto);

            assertThat(saved.getName()).isEqualTo("Lanzamiento");
        }

        @Test
        @DisplayName("rejects rename to a name held by another active timeline in the same org")
        void should_rejectRenameToOtherActiveName() {
            Timeline current = existing(7, "Hito");
            when(timelineRepository.findById(7)).thenReturn(Optional.of(current));
            when(timelineRepository.findByOrganizationAndName(org, "Lanzamiento"))
                    .thenReturn(Optional.of(existing(8, "Lanzamiento")));
            UpdateTimelineDto dto = new UpdateTimelineDto().setName("Lanzamiento");

            assertThatThrownBy(() -> sut.update(1, 7, dto))
                    .isInstanceOfSatisfying(ApiException.class, ex ->
                            assertThat(ex.getCode()).isEqualTo(ErrorCodes.TIMELINES_NAME_CONFLICT));
            verify(timelineRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("rename to the same name skips the uniqueness check")
        void should_skipCheck_whenSameName() {
            Timeline current = existing(7, "Hito");
            when(timelineRepository.findById(7)).thenReturn(Optional.of(current));
            when(timelineRepository.saveAndFlush(current)).thenReturn(current);
            UpdateTimelineDto dto = new UpdateTimelineDto().setName("Hito");

            sut.update(1, 7, dto);

            verify(timelineRepository, never()).findByOrganizationAndName(any(), any());
        }

        @Test
        @DisplayName("null name leaves the existing name untouched")
        void should_keepName_whenNameNull() {
            Timeline current = existing(7, "Hito");
            when(timelineRepository.findById(7)).thenReturn(Optional.of(current));
            when(timelineRepository.saveAndFlush(current)).thenReturn(current);

            Timeline saved = sut.update(1, 7, new UpdateTimelineDto());

            assertThat(saved.getName()).isEqualTo("Hito");
            verify(timelineRepository, never()).findByOrganizationAndName(any(), any());
        }
    }

    @Nested
    @DisplayName("softDelete")
    class SoftDelete {

        @Test
        @DisplayName("renames the row to free the (org_id, name) slot and stamps deleted_at")
        void should_renameAndStampDeletedAt() {
            Timeline current = existing(42, "Hito");
            when(timelineRepository.findById(42)).thenReturn(Optional.of(current));

            sut.softDelete(1, 42);

            assertThat(current.getName()).isEqualTo("Hito::deleted::42");
            assertThat(current.getDeletedAt()).isNotNull();
            verify(timelineRepository).save(current);
        }

        @Test
        @DisplayName("truncates the original name so the renamed value still fits the column length")
        void should_truncateLongNamesOnSoftDelete() {
            String longName = "x".repeat(TimelineService.MAX_NAME_LENGTH);
            Timeline current = existing(123456, longName);
            when(timelineRepository.findById(123456)).thenReturn(Optional.of(current));

            sut.softDelete(1, 123456);

            assertThat(current.getName()).hasSizeLessThanOrEqualTo(TimelineService.MAX_NAME_LENGTH);
            assertThat(current.getName()).endsWith("::deleted::123456");
        }
    }

    @Nested
    @DisplayName("buildSoftDeletedName helper")
    class BuildSoftDeletedNameHelper {

        @Test
        @DisplayName("appends the id suffix when the resulting value fits")
        void should_appendSuffix() {
            assertThat(TimelineService.buildSoftDeletedName("Hito", 42, 120))
                    .isEqualTo("Hito::deleted::42");
        }

        @Test
        @DisplayName("truncates the base name when needed to respect maxLength")
        void should_truncateWhenTooLong() {
            String result = TimelineService.buildSoftDeletedName("abcdef", 7, 12);
            assertThat(result).hasSize(12);
            assertThat(result).endsWith("::deleted::7");
        }

        @Test
        @DisplayName("substitutes a placeholder for null id (defensive)")
        void should_handleNullId() {
            assertThat(TimelineService.buildSoftDeletedName("Hito", null, 120))
                    .isEqualTo("Hito::deleted::?");
        }

        @Test
        @DisplayName("handles a null base name gracefully")
        void should_handleNullName() {
            assertThat(TimelineService.buildSoftDeletedName(null, 5, 120))
                    .isEqualTo("::deleted::5");
        }
    }
}
