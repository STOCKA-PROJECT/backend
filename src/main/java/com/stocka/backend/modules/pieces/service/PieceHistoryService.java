package com.stocka.backend.modules.pieces.service;

import org.springframework.stereotype.Service;

import com.stocka.backend.modules.pieces.entity.Piece;
import com.stocka.backend.modules.pieces.entity.PieceHistory;
import com.stocka.backend.modules.pieces.entity.PieceHistoryAction;
import com.stocka.backend.modules.pieces.entity.PieceStatus;
import com.stocka.backend.modules.pieces.repository.PieceHistoryRepository;
import com.stocka.backend.modules.users.entity.User;

/**
 * Records {@link PieceHistory} entries for every mutation on a {@link Piece}. Methods are
 * fine-grained on purpose so callers cannot forget to fill the right action / field. Mirrors the
 * pattern established by {@code OrganizationAuditService}.
 */
@Service
public class PieceHistoryService {
    private final PieceHistoryRepository repository;

    public PieceHistoryService(PieceHistoryRepository repository) {
        this.repository = repository;
    }

    public PieceHistory record(Piece piece, User actor, PieceHistoryAction action,
                               String fieldName, String oldValue, String newValue) {
        PieceHistory entry = new PieceHistory()
                .setPiece(piece)
                .setActor(actor)
                .setAction(action)
                .setFieldName(fieldName)
                .setOldValue(oldValue)
                .setNewValue(newValue);
        return repository.save(entry);
    }

    public PieceHistory recordCreated(Piece piece, User actor) {
        return record(piece, actor, PieceHistoryAction.PIECE_CREATED, null, null, piece.getName());
    }

    public PieceHistory recordDeleted(Piece piece, User actor) {
        return record(piece, actor, PieceHistoryAction.PIECE_DELETED, null, piece.getName(), null);
    }

    public PieceHistory recordUpdated(Piece piece, User actor, String fieldName, String oldValue, String newValue) {
        return record(piece, actor, PieceHistoryAction.PIECE_UPDATED, fieldName, oldValue, newValue);
    }

    public PieceHistory recordOwnerChanged(Piece piece, User actor, Integer oldOwnerId, Integer newOwnerId) {
        return record(piece, actor, PieceHistoryAction.OWNER_CHANGED, "owner",
                idOrNull(oldOwnerId), idOrNull(newOwnerId));
    }

    public PieceHistory recordLocationChanged(Piece piece, User actor, Integer oldLocationId, Integer newLocationId) {
        return record(piece, actor, PieceHistoryAction.LOCATION_CHANGED, "location",
                idOrNull(oldLocationId), idOrNull(newLocationId));
    }

    public PieceHistory recordStatusChanged(Piece piece, User actor, PieceStatus oldStatus, PieceStatus newStatus) {
        return record(piece, actor, PieceHistoryAction.STATUS_CHANGED, "status",
                oldStatus == null ? null : oldStatus.name(),
                newStatus == null ? null : newStatus.name());
    }

    public PieceHistory recordAttributeValueChanged(Piece piece, User actor, String attributeName, String oldValue, String newValue) {
        return record(piece, actor, PieceHistoryAction.ATTRIBUTE_VALUE_CHANGED, attributeName, oldValue, newValue);
    }

    public PieceHistory recordAttachmentAdded(Piece piece, User actor, String filename) {
        return record(piece, actor, PieceHistoryAction.ATTACHMENT_ADDED, "attachment", null, filename);
    }

    public PieceHistory recordAttachmentRemoved(Piece piece, User actor, String filename) {
        return record(piece, actor, PieceHistoryAction.ATTACHMENT_REMOVED, "attachment", filename, null);
    }

    private String idOrNull(Integer id) {
        return id == null ? null : id.toString();
    }
}
