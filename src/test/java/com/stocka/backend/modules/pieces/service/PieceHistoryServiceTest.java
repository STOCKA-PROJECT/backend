package com.stocka.backend.modules.pieces.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.stocka.backend.modules.pieces.entity.Piece;
import com.stocka.backend.modules.pieces.entity.PieceHistory;
import com.stocka.backend.modules.pieces.entity.PieceHistoryAction;
import com.stocka.backend.modules.pieces.entity.PieceStatus;
import com.stocka.backend.modules.pieces.repository.PieceHistoryRepository;
import com.stocka.backend.modules.users.entity.User;

@ExtendWith(MockitoExtension.class)
@DisplayName("PieceHistoryService")
class PieceHistoryServiceTest {

    @Mock private PieceHistoryRepository repository;
    @InjectMocks private PieceHistoryService sut;

    private Piece piece;
    private User actor;

    @BeforeEach
    void setUp() {
        piece = new Piece().setId(7).setName("Hammer");
        actor = new User().setId(11).setEmail("a@test.com");
        when(repository.save(any(PieceHistory.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Nested
    @DisplayName("recordCreated")
    class RecordCreated {
        @Test
        @DisplayName("should set action PIECE_CREATED with new value as the piece name")
        void should_persistEntry() {
            PieceHistory entry = sut.recordCreated(piece, actor);
            assertThat(entry.getAction()).isEqualTo(PieceHistoryAction.PIECE_CREATED);
            assertThat(entry.getOldValue()).isNull();
            assertThat(entry.getNewValue()).isEqualTo("Hammer");
            assertThat(entry.getPiece()).isSameAs(piece);
            assertThat(entry.getActor()).isSameAs(actor);
        }
    }

    @Nested
    @DisplayName("recordDeleted")
    class RecordDeleted {
        @Test
        @DisplayName("should set action PIECE_DELETED with old value as the piece name")
        void should_persistEntry() {
            PieceHistory entry = sut.recordDeleted(piece, actor);
            assertThat(entry.getAction()).isEqualTo(PieceHistoryAction.PIECE_DELETED);
            assertThat(entry.getOldValue()).isEqualTo("Hammer");
            assertThat(entry.getNewValue()).isNull();
        }
    }

    @Nested
    @DisplayName("recordOwnerChanged")
    class RecordOwnerChanged {
        @Test
        @DisplayName("should store both display names verbatim")
        void should_persistOwnerNames() {
            PieceHistory entry = sut.recordOwnerChanged(piece, actor, "Alice Smith", "Bob Jones");
            assertThat(entry.getAction()).isEqualTo(PieceHistoryAction.OWNER_CHANGED);
            assertThat(entry.getFieldName()).isEqualTo("owner");
            assertThat(entry.getOldValue()).isEqualTo("Alice Smith");
            assertThat(entry.getNewValue()).isEqualTo("Bob Jones");
        }

        @Test
        @DisplayName("should record null new value when clearing the owner")
        void should_recordNull_whenClearingOwner() {
            PieceHistory entry = sut.recordOwnerChanged(piece, actor, "Alice Smith", null);
            assertThat(entry.getOldValue()).isEqualTo("Alice Smith");
            assertThat(entry.getNewValue()).isNull();
        }

        @Test
        @DisplayName("should record null old value when assigning an owner for the first time")
        void should_recordNullOld_whenAssigningOwner() {
            PieceHistory entry = sut.recordOwnerChanged(piece, actor, null, "Bob Jones");
            assertThat(entry.getOldValue()).isNull();
            assertThat(entry.getNewValue()).isEqualTo("Bob Jones");
        }

        @Test
        @DisplayName("should preserve null on both sides when names are not available")
        void should_persistBothNulls_whenNamesUnavailable() {
            PieceHistory entry = sut.recordOwnerChanged(piece, actor, null, null);
            assertThat(entry.getOldValue()).isNull();
            assertThat(entry.getNewValue()).isNull();
        }
    }

    @Nested
    @DisplayName("recordLocationChanged")
    class RecordLocationChanged {
        @Test
        @DisplayName("should record both location names verbatim with field name 'location'")
        void should_persistLocationNames() {
            PieceHistory entry = sut.recordLocationChanged(piece, actor, "Warehouse A", "Warehouse B");
            assertThat(entry.getAction()).isEqualTo(PieceHistoryAction.LOCATION_CHANGED);
            assertThat(entry.getFieldName()).isEqualTo("location");
            assertThat(entry.getOldValue()).isEqualTo("Warehouse A");
            assertThat(entry.getNewValue()).isEqualTo("Warehouse B");
        }

        @Test
        @DisplayName("should record null old value when assigning a location for the first time")
        void should_recordNullOld_whenAssigningLocation() {
            PieceHistory entry = sut.recordLocationChanged(piece, actor, null, "Shelf 3");
            assertThat(entry.getOldValue()).isNull();
            assertThat(entry.getNewValue()).isEqualTo("Shelf 3");
        }

        @Test
        @DisplayName("should record null new value when clearing the location")
        void should_recordNullNew_whenClearingLocation() {
            PieceHistory entry = sut.recordLocationChanged(piece, actor, "Shelf 3", null);
            assertThat(entry.getOldValue()).isEqualTo("Shelf 3");
            assertThat(entry.getNewValue()).isNull();
        }
    }

    @Nested
    @DisplayName("recordStatusChanged")
    class RecordStatusChanged {
        @Test
        @DisplayName("should record both statuses by name")
        void should_persistStatusNames() {
            PieceHistory entry = sut.recordStatusChanged(piece, actor, PieceStatus.PENDING, PieceStatus.ACTIVE);
            assertThat(entry.getAction()).isEqualTo(PieceHistoryAction.STATUS_CHANGED);
            assertThat(entry.getFieldName()).isEqualTo("status");
            assertThat(entry.getOldValue()).isEqualTo("PENDING");
            assertThat(entry.getNewValue()).isEqualTo("ACTIVE");
        }
    }

    @Nested
    @DisplayName("recordAttributeValueChanged")
    class RecordAttributeValueChanged {
        @Test
        @DisplayName("should put attribute name in field_name")
        void should_persistAttributeNameAndValues() {
            PieceHistory entry = sut.recordAttributeValueChanged(piece, actor, "color", "red", "blue");
            assertThat(entry.getAction()).isEqualTo(PieceHistoryAction.ATTRIBUTE_VALUE_CHANGED);
            assertThat(entry.getFieldName()).isEqualTo("color");
            assertThat(entry.getOldValue()).isEqualTo("red");
            assertThat(entry.getNewValue()).isEqualTo("blue");
        }
    }

    @Nested
    @DisplayName("recordAttachmentAdded / removed")
    class RecordAttachment {
        @Test
        @DisplayName("added should leave old value null and new value as filename")
        void added() {
            PieceHistory entry = sut.recordAttachmentAdded(piece, actor, "manual.pdf");
            assertThat(entry.getAction()).isEqualTo(PieceHistoryAction.ATTACHMENT_ADDED);
            assertThat(entry.getFieldName()).isEqualTo("attachment");
            assertThat(entry.getOldValue()).isNull();
            assertThat(entry.getNewValue()).isEqualTo("manual.pdf");
        }

        @Test
        @DisplayName("removed should leave new value null and old value as filename")
        void removed() {
            PieceHistory entry = sut.recordAttachmentRemoved(piece, actor, "photo.jpg");
            assertThat(entry.getAction()).isEqualTo(PieceHistoryAction.ATTACHMENT_REMOVED);
            assertThat(entry.getOldValue()).isEqualTo("photo.jpg");
            assertThat(entry.getNewValue()).isNull();
        }
    }

    @Nested
    @DisplayName("record (generic)")
    class RecordGeneric {
        @Test
        @DisplayName("should persist exactly the provided action/field/values")
        void should_persistGenericEntry() {
            sut.record(piece, actor, PieceHistoryAction.PIECE_UPDATED, "name", "old", "new");
            ArgumentCaptor<PieceHistory> captor = ArgumentCaptor.forClass(PieceHistory.class);
            org.mockito.Mockito.verify(repository).save(captor.capture());
            PieceHistory saved = captor.getValue();
            assertThat(saved.getAction()).isEqualTo(PieceHistoryAction.PIECE_UPDATED);
            assertThat(saved.getFieldName()).isEqualTo("name");
            assertThat(saved.getOldValue()).isEqualTo("old");
            assertThat(saved.getNewValue()).isEqualTo("new");
        }
    }
}
