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

    /**
     * Records an owner change. Both arguments are the human-readable display name (or e-mail when
     * the name is empty). Names are stored directly so the audit entry remains meaningful even if
     * the user is later removed from the organization or renamed.
     *
     * @param piece           the piece being modified
     * @param actor           the user performing the change
     * @param oldOwnerName    previous owner display name, or {@code null} if there was no owner
     * @param newOwnerName    new owner display name, or {@code null} when clearing the owner
     * @return the persisted history entry
     */
    public PieceHistory recordOwnerChanged(Piece piece, User actor, String oldOwnerName, String newOwnerName) {
        return record(piece, actor, PieceHistoryAction.OWNER_CHANGED, "owner", oldOwnerName, newOwnerName);
    }

    /**
     * Records a location change. Both arguments are the location name at the time of the change.
     * Names are stored directly so the audit entry remains meaningful even if the location is
     * later renamed or soft-deleted.
     *
     * @param piece            the piece being modified
     * @param actor            the user performing the change
     * @param oldLocationName  previous location name, or {@code null} if the piece had none
     * @param newLocationName  new location name, or {@code null} when clearing the location
     * @return the persisted history entry
     */
    public PieceHistory recordLocationChanged(Piece piece, User actor, String oldLocationName, String newLocationName) {
        return record(piece, actor, PieceHistoryAction.LOCATION_CHANGED, "location", oldLocationName, newLocationName);
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

    /**
     * Records a change in the set of types attached to {@code piece}. Both arguments are the
     * comma-separated, lexicographically-sorted type names so the diff stays human-readable.
     */
    public PieceHistory recordPieceTypesChanged(Piece piece, User actor, String oldTypes, String newTypes) {
        return record(piece, actor, PieceHistoryAction.PIECE_TYPES_CHANGED, "pieceTypes", oldTypes, newTypes);
    }
}
