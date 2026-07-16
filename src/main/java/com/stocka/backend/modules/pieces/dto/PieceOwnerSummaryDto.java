package com.stocka.backend.modules.pieces.dto;

import com.stocka.backend.modules.contacts.service.ContactService;
import com.stocka.backend.modules.pieces.entity.Piece;
import com.stocka.backend.modules.users.entity.User;

/**
 * Compact, kind-agnostic view of a piece's owner, embedded in piece responses so clients can
 * render the owner's name without resolving ids against the member or contact lists.
 *
 * <p>{@code kind} tells which directory {@code id} points into: {@link OwnerKind#USER} for the
 * user id of an organization member, {@link OwnerKind#CONTACT} for a contact id of the
 * organization's contact directory.
 */
public record PieceOwnerSummaryDto(OwnerKind kind, Integer id, String displayName, String email) {

    /** Which directory a piece owner belongs to. */
    public enum OwnerKind { USER, CONTACT }

    /**
     * Builds the summary for {@code piece}'s current owner.
     *
     * @param piece the piece whose owner to summarize
     * @return the summary, or {@code null} when the piece has no owner
     */
    public static PieceOwnerSummaryDto from(Piece piece) {
        if (piece.getOwner() != null) {
            User user = piece.getOwner();
            return new PieceOwnerSummaryDto(
                    OwnerKind.USER, user.getId(), displayUserName(user), user.getEmail());
        }
        if (piece.getOwnerContact() != null) {
            return new PieceOwnerSummaryDto(
                    OwnerKind.CONTACT,
                    piece.getOwnerContact().getId(),
                    ContactService.displayName(piece.getOwnerContact()),
                    piece.getOwnerContact().getEmail());
        }
        return null;
    }

    private static String displayUserName(User user) {
        String first = user.getName();
        String last = user.getLastName();
        String full = ((first == null ? "" : first) + " " + (last == null ? "" : last)).trim();
        if (!full.isEmpty()) return full;
        return user.getEmail();
    }
}
