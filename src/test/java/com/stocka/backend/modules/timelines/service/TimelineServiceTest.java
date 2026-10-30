package com.stocka.backend.modules.timelines.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.stocka.backend.modules.common.error.ApiException;
import com.stocka.backend.modules.common.error.ErrorCodes;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.service.OrganizationService;
import com.stocka.backend.modules.timelines.dto.CreateTimelineDto;
import com.stocka.backend.modules.timelines.dto.DuplicateTimelineDto;
import com.stocka.backend.modules.timelines.dto.UpdateTimelineDto;
import com.stocka.backend.modules.timelines.entity.Timeline;
import com.stocka.backend.modules.timelines.entity.TimelineScene;
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
        sut = new TimelineService(timelineRepository, organizationService, sceneRepository, messages());
        // Lenient because the static-helper tests don't go through the service layer.
        lenient().when(organizationService.findById(1)).thenReturn(org);
        LocaleContextHolder.setLocale(Locale.of("ca"));
    }

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    /** The copy-name messages of the real bundles, formatted like production (always MessageFormat). */
    private static StaticMessageSource messages() {
        StaticMessageSource source = new StaticMessageSource();
        source.setAlwaysUseMessageFormat(true);
        source.addMessage("timelines.copy_name", Locale.of("ca"), "{0} (còpia)");
        source.addMessage("timelines.copy_name_numbered", Locale.of("ca"), "{0} (còpia {1})");
        source.addMessage("timelines.copy_name", Locale.of("es"), "{0} (copia)");
        source.addMessage("timelines.copy_name_numbered", Locale.of("es"), "{0} (copia {1})");
        source.addMessage("timelines.copy_name", Locale.of("en"), "{0} (copy)");
        source.addMessage("timelines.copy_name_numbered", Locale.of("en"), "{0} (copy {1})");
        return source;
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
    @DisplayName("duplicate")
    class Duplicate {

        private Timeline source;

        @BeforeEach
        void stubSource() {
            source = existing(7, "Belén 2026");
            lenient().when(timelineRepository.findById(7)).thenReturn(Optional.of(source));
            lenient().when(timelineRepository.saveAndFlush(any(Timeline.class))).thenAnswer(inv -> {
                Timeline t = inv.getArgument(0);
                t.setId(8);
                return t;
            });
        }

        private void taken(String... names) {
            List<Timeline> stored = new ArrayList<>();
            stored.add(source);
            for (int i = 0; i < names.length; i++) {
                stored.add(existing(100 + i, names[i]));
            }
            when(timelineRepository.findByOrganization(org)).thenReturn(stored);
        }

        @Nested
        @DisplayName("automatic name")
        class AutomaticName {

            @Test
            @DisplayName("a null body names the copy \"<name> (còpia)\" in the request locale")
            void should_nameCopy_whenBodyNull() {
                taken();

                Timeline copy = sut.duplicate(1, 7, null);

                assertThat(copy.getName()).isEqualTo("Belén 2026 (còpia)");
                assertThat(copy.getId()).isEqualTo(8);
                assertThat(copy.getOrganization()).isSameAs(org);
            }

            @Test
            @DisplayName("a body without name behaves like no body")
            void should_nameCopy_whenNameNull() {
                taken();

                Timeline copy = sut.duplicate(1, 7, new DuplicateTimelineDto());

                assertThat(copy.getName()).isEqualTo("Belén 2026 (còpia)");
                verify(timelineRepository, never()).findByOrganizationAndName(any(), any());
            }

            @Test
            @DisplayName("numbers the copy from 2 when \"(còpia)\" is taken")
            void should_number_whenFirstCopyTaken() {
                taken("Belén 2026 (còpia)");

                assertThat(sut.duplicate(1, 7, null).getName()).isEqualTo("Belén 2026 (còpia 2)");
            }

            @Test
            @DisplayName("takes the first free number, filling gaps")
            void should_takeFirstFreeNumber() {
                taken("Belén 2026 (còpia)", "Belén 2026 (còpia 2)", "Belén 2026 (còpia 4)");

                assertThat(sut.duplicate(1, 7, null).getName()).isEqualTo("Belén 2026 (còpia 3)");
            }

            @Test
            @DisplayName("formats the number without digit grouping")
            void should_notGroupDigits() {
                List<String> names = new ArrayList<>();
                names.add("Belén 2026 (còpia)");
                for (int n = 2; n < TimelineService.MAX_COPY_NUMBER; n++) {
                    names.add("Belén 2026 (còpia " + n + ")");
                }
                taken(names.toArray(String[]::new));

                assertThat(sut.duplicate(1, 7, null).getName())
                        .isEqualTo("Belén 2026 (còpia " + TimelineService.MAX_COPY_NUMBER + ")");
            }

            @Test
            @DisplayName("rejects with TIMELINES_NAME_CONFLICT when every copy number is taken")
            void should_reject_whenNoCopyNameFree() {
                List<String> names = new ArrayList<>();
                names.add("Belén 2026 (còpia)");
                for (int n = 2; n <= TimelineService.MAX_COPY_NUMBER; n++) {
                    names.add("Belén 2026 (còpia " + n + ")");
                }
                taken(names.toArray(String[]::new));

                assertThatThrownBy(() -> sut.duplicate(1, 7, null))
                        .isInstanceOfSatisfying(ApiException.class, ex -> {
                            assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                            assertThat(ex.getCode()).isEqualTo(ErrorCodes.TIMELINES_NAME_CONFLICT);
                        });
                verify(timelineRepository, never()).saveAndFlush(any());
            }

            @Test
            @DisplayName("uses the Spanish suffix for an es request")
            void should_useSpanishSuffix() {
                LocaleContextHolder.setLocale(Locale.of("es"));
                taken("Belén 2026 (copia)");

                assertThat(sut.duplicate(1, 7, null).getName()).isEqualTo("Belén 2026 (copia 2)");
            }

            @Test
            @DisplayName("uses the English suffix for an en request")
            void should_useEnglishSuffix() {
                LocaleContextHolder.setLocale(Locale.of("en"));
                taken();

                assertThat(sut.duplicate(1, 7, null).getName()).isEqualTo("Belén 2026 (copy)");
            }

            @Test
            @DisplayName("cuts a long base name so the copy name still fits the column")
            void should_truncateLongBase() {
                source.setName("x".repeat(TimelineService.MAX_NAME_LENGTH));
                taken();

                String name = sut.duplicate(1, 7, null).getName();

                assertThat(name).hasSize(TimelineService.MAX_NAME_LENGTH).endsWith("x (còpia)");
            }

            @Test
            @DisplayName("cuts the base further for a numbered copy and drops the trailing space of the cut")
            void should_truncateLongBase_numberedAndStripped() {
                // 120 chars: 109 x, a space at index 109, 10 y. " (còpia)" (8) keeps 112 chars of the
                // base; " (còpia 2)" (10) keeps 110, cutting right after the space, which is dropped.
                String xs = "x".repeat(TimelineService.MAX_NAME_LENGTH - 11);
                source.setName(xs + " " + "y".repeat(10));
                taken(xs + " yy (còpia)");

                String name = sut.duplicate(1, 7, null).getName();

                assertThat(name).isEqualTo(xs + " (còpia 2)");
                assertThat(name.length()).isLessThanOrEqualTo(TimelineService.MAX_NAME_LENGTH);
            }

            @Test
            @DisplayName("a soft-deleted copy does not block its name (deleted rows are not listed)")
            void should_ignoreSoftDeletedRows() {
                // findByOrganization honours @SQLRestriction; the mangled name never collides anyway.
                taken("Belén 2026 (còpia)::deleted::55");

                assertThat(sut.duplicate(1, 7, null).getName()).isEqualTo("Belén 2026 (còpia)");
            }
        }

        @Nested
        @DisplayName("explicit name")
        class ExplicitName {

            @Test
            @DisplayName("uses the given name when it is free")
            void should_useGivenName() {
                when(timelineRepository.findByOrganizationAndName(org, "Belén 2027")).thenReturn(Optional.empty());

                Timeline copy = sut.duplicate(1, 7, new DuplicateTimelineDto().setName("Belén 2027"));

                assertThat(copy.getName()).isEqualTo("Belén 2027");
                verify(timelineRepository, never()).findByOrganization(any());
            }

            @Test
            @DisplayName("trims the given name before checking it")
            void should_trimGivenName() {
                when(timelineRepository.findByOrganizationAndName(org, "Belén 2027")).thenReturn(Optional.empty());

                Timeline copy = sut.duplicate(1, 7, new DuplicateTimelineDto().setName("  Belén 2027 "));

                assertThat(copy.getName()).isEqualTo("Belén 2027");
            }

            @Test
            @DisplayName("rejects a taken name with TIMELINES_NAME_CONFLICT")
            void should_rejectTakenName() {
                when(timelineRepository.findByOrganizationAndName(org, "Belén 2026")).thenReturn(Optional.of(source));

                assertThatThrownBy(() -> sut.duplicate(1, 7, new DuplicateTimelineDto().setName("Belén 2026")))
                        .isInstanceOfSatisfying(ApiException.class, ex -> {
                            assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                            assertThat(ex.getCode()).isEqualTo(ErrorCodes.TIMELINES_NAME_CONFLICT);
                        });
                verify(timelineRepository, never()).saveAndFlush(any());
                verify(sceneRepository, never()).save(any());
            }

            @Test
            @DisplayName("rejects a blank name with HTTP 400")
            void should_rejectBlankName() {
                assertThatThrownBy(() -> sut.duplicate(1, 7, new DuplicateTimelineDto().setName("  ")))
                        .isInstanceOf(ResponseStatusException.class);
                verify(timelineRepository, never()).saveAndFlush(any());
            }

            @Test
            @DisplayName("rejects a name longer than the column with HTTP 400")
            void should_rejectTooLongName() {
                String tooLong = "x".repeat(TimelineService.MAX_NAME_LENGTH + 1);

                assertThatThrownBy(() -> sut.duplicate(1, 7, new DuplicateTimelineDto().setName(tooLong)))
                        .isInstanceOf(ResponseStatusException.class)
                        .hasMessageContaining(String.valueOf(TimelineService.MAX_NAME_LENGTH));
            }
        }

        @Nested
        @DisplayName("scene")
        class Scene {

            @Test
            @DisplayName("copies the document and schema version into a new scene at version 1")
            void should_copyScene() {
                taken();
                TimelineScene original = new TimelineScene()
                        .setTimeline(source)
                        .setSchemaVersion(3)
                        .setDocument("{\"clips\":[{\"id\":\"clip-1\"}]}")
                        .setVersion(17);
                original.setId(40);
                when(sceneRepository.findByTimeline(source)).thenReturn(Optional.of(original));

                Timeline copy = sut.duplicate(1, 7, null);

                ArgumentCaptor<TimelineScene> saved = ArgumentCaptor.forClass(TimelineScene.class);
                verify(sceneRepository).save(saved.capture());
                assertThat(saved.getValue()).isNotSameAs(original);
                assertThat(saved.getValue().getId()).isNull();
                assertThat(saved.getValue().getTimeline()).isSameAs(copy);
                assertThat(saved.getValue().getSchemaVersion()).isEqualTo(3);
                assertThat(saved.getValue().getDocument()).isEqualTo("{\"clips\":[{\"id\":\"clip-1\"}]}");
                assertThat(saved.getValue().getVersion()).isEqualTo(1);
                // The source scene is left as it was.
                assertThat(original.getVersion()).isEqualTo(17);
                assertThat(original.getTimeline()).isSameAs(source);
            }

            @Test
            @DisplayName("a timeline that was never edited gives a copy without scene")
            void should_skipScene_whenSourceHasNone() {
                taken();
                when(sceneRepository.findByTimeline(source)).thenReturn(Optional.empty());

                sut.duplicate(1, 7, null);

                verify(sceneRepository, never()).save(any());
            }
        }

        @Nested
        @DisplayName("errors")
        class Errors {

            @Test
            @DisplayName("rejects a missing source with TIMELINES_NOT_FOUND")
            void should_rejectMissingSource() {
                when(timelineRepository.findById(9)).thenReturn(Optional.empty());

                assertThatThrownBy(() -> sut.duplicate(1, 9, null))
                        .isInstanceOfSatisfying(ApiException.class, ex -> {
                            assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                            assertThat(ex.getCode()).isEqualTo(ErrorCodes.TIMELINES_NOT_FOUND);
                        });
                verify(timelineRepository, never()).saveAndFlush(any());
            }

            @Test
            @DisplayName("rejects a source of another organization with TIMELINES_NOT_FOUND")
            void should_rejectForeignSource() {
                Organization other = new Organization();
                other.setId(99);
                Timeline foreign = new Timeline().setOrganization(other).setName("Aliè");
                foreign.setId(9);
                when(timelineRepository.findById(9)).thenReturn(Optional.of(foreign));

                assertThatThrownBy(() -> sut.duplicate(1, 9, null))
                        .isInstanceOfSatisfying(ApiException.class, ex ->
                                assertThat(ex.getCode()).isEqualTo(ErrorCodes.TIMELINES_NOT_FOUND));
                verify(timelineRepository, never()).saveAndFlush(any());
                verify(sceneRepository, never()).save(any());
            }

            @Test
            @DisplayName("translates a concurrent uk_timeline_org_name violation into a 409 and copies no scene")
            void should_translateRace_intoConflict() {
                taken();
                when(timelineRepository.saveAndFlush(any(Timeline.class)))
                        .thenThrow(new DataIntegrityViolationException("Duplicate entry"));

                assertThatThrownBy(() -> sut.duplicate(1, 7, null))
                        .isInstanceOfSatisfying(ApiException.class, ex -> {
                            assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                            assertThat(ex.getCode()).isEqualTo(ErrorCodes.TIMELINES_NAME_CONFLICT);
                        });
                verify(sceneRepository, never()).save(any());
            }
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
