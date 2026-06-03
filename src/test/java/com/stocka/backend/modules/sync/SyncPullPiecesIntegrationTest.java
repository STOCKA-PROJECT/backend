package com.stocka.backend.modules.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.repository.OrganizationRepository;
import com.stocka.backend.modules.pieces.dto.CreatePieceDto;
import com.stocka.backend.modules.pieces.entity.Piece;
import com.stocka.backend.modules.pieces.service.PieceService;
import com.stocka.backend.modules.piecetypes.dto.CreatePieceTypeAttributeDto;
import com.stocka.backend.modules.piecetypes.dto.CreatePieceTypeDto;
import com.stocka.backend.modules.piecetypes.entity.AttributeType;
import com.stocka.backend.modules.piecetypes.entity.PieceType;
import com.stocka.backend.modules.piecetypes.service.PieceTypeService;
import com.stocka.backend.modules.sync.dto.PieceSyncDto;
import com.stocka.backend.modules.sync.dto.PieceTypeAttributeSyncDto;
import com.stocka.backend.modules.sync.dto.PieceTypeSyncDto;
import com.stocka.backend.modules.sync.dto.SyncChangesResponse;
import com.stocka.backend.modules.sync.service.SyncService;

/**
 * Verifies the pieces aggregate in the sync pull feed: scalar fields, type references exposed by
 * {@code syncId}, and tombstone delivery on soft-delete.
 */
@SpringBootTest
@DisplayName("Sync pull feed (pieces aggregate)")
class SyncPullPiecesIntegrationTest {

    @Autowired
    private PieceService pieceService;

    @Autowired
    private PieceTypeService pieceTypeService;

    @Autowired
    private SyncService syncService;

    @Autowired
    private OrganizationRepository organizationRepository;

    private Integer orgId;

    @BeforeEach
    void setUp() {
        Organization org = organizationRepository.save(new Organization()
                .setName("Pieces Org")
                .setSlug("pieces-org-" + UUID.randomUUID()));
        orgId = org.getId();
    }

    @Test
    @DisplayName("returns the piece aggregate with type refs by syncId, and tombstones on delete")
    void should_pull_piece_aggregate_and_tombstone() {
        PieceType type = pieceTypeService.create(orgId, new CreatePieceTypeDto().setName("Tornillos"));
        Piece piece = pieceService.create(orgId, new CreatePieceDto()
                .setName("Tornillo M3")
                .setSerialNumber("S-1")
                .setPieceTypeIds(List.of(type.getId())));

        SyncChangesResponse pull = syncService.pull(orgId, Map.of(), SyncService.DEFAULT_LIMIT);

        assertThat(pull.changes().pieces()).hasSize(1);
        PieceSyncDto dto = pull.changes().pieces().get(0);
        assertThat(dto.syncId()).isEqualTo(piece.getSyncId());
        assertThat(dto.name()).isEqualTo("Tornillo M3");
        assertThat(dto.serialNumber()).isEqualTo("S-1");
        assertThat(dto.deletedAt()).isNull();
        assertThat(dto.pieceTypeSyncIds()).containsExactly(type.getSyncId());
        assertThat(pull.checkpoint()).containsKey("pieces");

        long pieceRev = dto.rev();

        // Soft-delete: the piece must come back as a tombstone on the next pull.
        pieceService.softDelete(orgId, piece.getId());
        SyncChangesResponse afterDelete = syncService.pull(
                orgId, Map.of("pieces", pieceRev), SyncService.DEFAULT_LIMIT);

        assertThat(afterDelete.changes().pieces()).hasSize(1);
        PieceSyncDto tombstone = afterDelete.changes().pieces().get(0);
        assertThat(tombstone.syncId()).isEqualTo(piece.getSyncId());
        assertThat(tombstone.deletedAt()).isNotNull();
        assertThat(tombstone.rev()).isGreaterThan(pieceRev);
    }

    @Test
    @DisplayName("returns piece types and their attribute definitions in the feed")
    void should_pull_types_and_attributes() {
        PieceType type = pieceTypeService.create(orgId, new CreatePieceTypeDto()
                .setName("Resistencias")
                .setAttributes(List.of(new CreatePieceTypeAttributeDto()
                        .setName("ohms")
                        .setDisplayName("Ohmios")
                        .setType(AttributeType.INTEGER)
                        .setRequired(true)
                        .setPosition(0))));

        SyncChangesResponse pull = syncService.pull(orgId, Map.of(), SyncService.DEFAULT_LIMIT);

        assertThat(pull.changes().pieceTypes())
                .extracting(PieceTypeSyncDto::syncId)
                .contains(type.getSyncId());
        assertThat(pull.changes().pieceTypeAttributes()).hasSize(1);
        PieceTypeAttributeSyncDto attr = pull.changes().pieceTypeAttributes().get(0);
        assertThat(attr.pieceTypeSyncId()).isEqualTo(type.getSyncId());
        assertThat(attr.name()).isEqualTo("ohms");
        assertThat(attr.type()).isEqualTo("INTEGER");
        assertThat(attr.required()).isTrue();
        assertThat(attr.position()).isZero();
        assertThat(pull.checkpoint())
                .containsKeys("pieceTypes", "pieceTypeAttributes", "locations",
                        "orgAttributes", "pieces", "attachments");
    }
}
