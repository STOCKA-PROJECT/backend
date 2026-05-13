package com.stocka.backend.modules.notifications.preferences.dto;

import java.util.Set;

import com.stocka.backend.modules.notifications.preferences.entity.LifecycleAction;
import com.stocka.backend.modules.notifications.preferences.entity.PieceScope;

/**
 * Mutable view of a {@link com.stocka.backend.modules.notifications.preferences.entity.NotificationPreference}
 * used by the {@code PUT} endpoint. Empty sets mean "do not notify me" for that resource —
 * a missing field, however, is rejected as a validation error by the service layer.
 */
public class UpdateNotificationPreferenceDto {
    private Set<LifecycleAction> pieces;
    private PieceScope pieceScope;
    private Set<LifecycleAction> locations;
    private Set<LifecycleAction> pieceTypes;

    public Set<LifecycleAction> getPieces() { return pieces; }
    public UpdateNotificationPreferenceDto setPieces(Set<LifecycleAction> pieces) {
        this.pieces = pieces;
        return this;
    }

    public PieceScope getPieceScope() { return pieceScope; }
    public UpdateNotificationPreferenceDto setPieceScope(PieceScope pieceScope) {
        this.pieceScope = pieceScope;
        return this;
    }

    public Set<LifecycleAction> getLocations() { return locations; }
    public UpdateNotificationPreferenceDto setLocations(Set<LifecycleAction> locations) {
        this.locations = locations;
        return this;
    }

    public Set<LifecycleAction> getPieceTypes() { return pieceTypes; }
    public UpdateNotificationPreferenceDto setPieceTypes(Set<LifecycleAction> pieceTypes) {
        this.pieceTypes = pieceTypes;
        return this;
    }
}
