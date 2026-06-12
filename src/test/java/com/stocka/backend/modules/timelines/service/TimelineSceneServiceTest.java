package com.stocka.backend.modules.timelines.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
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
import org.springframework.http.HttpStatus;

import com.stocka.backend.modules.common.error.ApiException;
import com.stocka.backend.modules.common.error.ErrorCodes;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.pieces.entity.Piece;
import com.stocka.backend.modules.pieces.repository.PieceRepository;
import com.stocka.backend.modules.piecetypes.entity.PieceType;
import com.stocka.backend.modules.piecetypes.entity.PieceTypeAction;
import com.stocka.backend.modules.piecetypes.repository.PieceTypeActionRepository;
import com.stocka.backend.modules.timelines.dto.UpsertTimelineSceneDto;
import com.stocka.backend.modules.timelines.entity.Timeline;
import com.stocka.backend.modules.timelines.entity.TimelineScene;
import com.stocka.backend.modules.timelines.repository.TimelineSceneRepository;

import tools.jackson.databind.JsonNode;

@ExtendWith(MockitoExtension.class)
@DisplayName("TimelineSceneService")
class TimelineSceneServiceTest {

    @Mock TimelineSceneRepository sceneRepository;
    @Mock TimelineService timelineService;
    @Mock PieceRepository pieceRepository;
    @Mock PieceTypeActionRepository actionRepository;

    private final TimelineSceneJsonCodec codec = new TimelineSceneJsonCodec();
    private TimelineSceneService sut;

    private final Organization org = new Organization();
    private Timeline timeline;

    @BeforeEach
    void setUp() {
        org.setId(1);
        timeline = new Timeline().setOrganization(org);
        timeline.setId(10);
        sut = new TimelineSceneService(sceneRepository, timelineService, codec, pieceRepository, actionRepository);
        lenient().when(timelineService.findInOrg(1, 10)).thenReturn(timeline);
        lenient().when(pieceRepository.findAllById(anyIterable())).thenReturn(List.of());
        lenient().when(actionRepository.findAllById(anyIterable())).thenReturn(List.of());
        lenient().when(sceneRepository.save(any(TimelineScene.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private UpsertTimelineSceneDto dto(String json, Integer version) {
        JsonNode doc = json == null ? null : codec.deserialize(json);
        return new UpsertTimelineSceneDto().setSchemaVersion(1).setDocument(doc).setVersion(version);
    }

    @Nested
    @DisplayName("upsert — create")
    class Create {

        @Test
        @DisplayName("creates a scene with version 1 when none exists")
        void should_createWhenNoneExists() {
            when(sceneRepository.findByTimeline(timeline)).thenReturn(Optional.empty());

            TimelineScene saved = sut.upsert(1, 10, dto("{\"board\":{\"width\":100}}", null));

            assertThat(saved.getVersion()).isEqualTo(1);
            assertThat(saved.getTimeline()).isSameAs(timeline);
            assertThat(saved.getDocument()).contains("\"width\":100");
            verify(sceneRepository).save(saved);
        }

        @Test
        @DisplayName("rejects a null document with 400 document_invalid")
        void should_rejectNullDocument() {
            assertThatThrownBy(() -> sut.upsert(1, 10, dto(null, null)))
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(ex.getCode()).isEqualTo(ErrorCodes.TIMELINE_SCENE_DOCUMENT_INVALID);
                    });
            verify(sceneRepository, never()).save(any());
        }

        @Test
        @DisplayName("rejects an oversized document with 400 document_invalid")
        void should_rejectOversizedDocument() {
            String huge = "{\"x\":\"" + "a".repeat(TimelineSceneService.MAX_DOCUMENT_BYTES + 10) + "\"}";

            assertThatThrownBy(() -> sut.upsert(1, 10, dto(huge, null)))
                    .isInstanceOfSatisfying(ApiException.class, ex ->
                            assertThat(ex.getCode()).isEqualTo(ErrorCodes.TIMELINE_SCENE_DOCUMENT_INVALID));
        }
    }

    @Nested
    @DisplayName("upsert — update / concurrency")
    class Update {

        private TimelineScene existing(int version) {
            TimelineScene s = new TimelineScene().setTimeline(timeline).setVersion(version);
            s.setId(5);
            return s;
        }

        @Test
        @DisplayName("increments version when the provided version matches")
        void should_incrementVersion_whenMatch() {
            when(sceneRepository.findByTimeline(timeline)).thenReturn(Optional.of(existing(3)));

            TimelineScene saved = sut.upsert(1, 10, dto("{}", 3));

            assertThat(saved.getVersion()).isEqualTo(4);
        }

        @Test
        @DisplayName("rejects a stale version with 409 version_conflict")
        void should_rejectStaleVersion() {
            when(sceneRepository.findByTimeline(timeline)).thenReturn(Optional.of(existing(5)));

            assertThatThrownBy(() -> sut.upsert(1, 10, dto("{}", 3)))
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(ex.getCode()).isEqualTo(ErrorCodes.TIMELINE_SCENE_VERSION_CONFLICT);
                    });
            verify(sceneRepository, never()).save(any());
        }

        @Test
        @DisplayName("skips the concurrency check when no version is provided")
        void should_skipCheck_whenVersionNull() {
            when(sceneRepository.findByTimeline(timeline)).thenReturn(Optional.of(existing(7)));

            TimelineScene saved = sut.upsert(1, 10, dto("{}", null));

            assertThat(saved.getVersion()).isEqualTo(8);
        }
    }

    @Nested
    @DisplayName("upsert — reference validation")
    class References {

        @Test
        @DisplayName("rejects an item referencing an unknown layer with 400 invalid_reference")
        void should_rejectDanglingLayerRef() {
            String doc = "{\"layers\":[{\"id\":\"l1\"}],\"items\":[{\"id\":\"i1\",\"layerId\":\"l9\"}]}";

            assertThatThrownBy(() -> sut.upsert(1, 10, dto(doc, null)))
                    .isInstanceOfSatisfying(ApiException.class, ex ->
                            assertThat(ex.getCode()).isEqualTo(ErrorCodes.TIMELINE_SCENE_INVALID_REFERENCE));
        }

        @Test
        @DisplayName("rejects a piece that belongs to another organization with 400 invalid_reference")
        void should_rejectForeignPiece() {
            Organization other = new Organization();
            other.setId(2);
            Piece foreign = new Piece();
            foreign.setOrganization(other);
            when(pieceRepository.findAllById(anyIterable())).thenReturn(List.of(foreign));
            String doc = "{\"layers\":[{\"id\":\"l1\"}],"
                    + "\"items\":[{\"id\":\"i1\",\"layerId\":\"l1\",\"pieceId\":42}]}";

            assertThatThrownBy(() -> sut.upsert(1, 10, dto(doc, null)))
                    .isInstanceOfSatisfying(ApiException.class, ex ->
                            assertThat(ex.getCode()).isEqualTo(ErrorCodes.TIMELINE_SCENE_INVALID_REFERENCE));
        }

        @Test
        @DisplayName("accepts a fully consistent document with in-org references")
        void should_acceptConsistentDocument() {
            when(sceneRepository.findByTimeline(timeline)).thenReturn(Optional.empty());
            Piece piece = new Piece();
            piece.setOrganization(org);
            when(pieceRepository.findAllById(anyIterable())).thenReturn(List.of(piece));
            PieceType type = new PieceType().setOrganization(org);
            PieceTypeAction action = new PieceTypeAction().setPieceType(type);
            when(actionRepository.findAllById(anyIterable())).thenReturn(List.of(action));
            String doc = "{"
                    + "\"layers\":[{\"id\":\"l1\"}],"
                    + "\"items\":[{\"id\":\"i1\",\"layerId\":\"l1\",\"pieceId\":42}],"
                    + "\"tracks\":[{\"id\":\"t1\",\"itemId\":\"i1\"}],"
                    + "\"clips\":[{\"id\":\"c1\",\"trackId\":\"t1\",\"pieceTypeActionId\":7}]"
                    + "}";

            TimelineScene saved = sut.upsert(1, 10, dto(doc, null));

            assertThat(saved.getVersion()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("find")
    class Find {

        @Test
        @DisplayName("returns the scene of the timeline when present")
        void should_returnScene() {
            TimelineScene scene = new TimelineScene().setTimeline(timeline);
            when(sceneRepository.findByTimeline(timeline)).thenReturn(Optional.of(scene));

            assertThat(sut.find(1, 10)).containsSame(scene);
        }

        @Test
        @DisplayName("returns empty when the timeline has no scene")
        void should_returnEmpty() {
            when(sceneRepository.findByTimeline(timeline)).thenReturn(Optional.empty());

            assertThat(sut.find(1, 10)).isEmpty();
        }
    }
}
