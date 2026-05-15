package com.stocka.backend.modules.notifications.preferences.dto;

import java.util.EnumSet;
import java.util.Set;

import com.stocka.backend.modules.notifications.preferences.entity.LifecycleAction;
import com.stocka.backend.modules.notifications.preferences.entity.NotificationPreference;
import com.stocka.backend.modules.notifications.preferences.entity.PieceScope;
import com.stocka.backend.modules.organizations.entity.Organization;

public record NotificationPreferenceResponseDto(
        Integer organizationId,
        String organizationName,
        String organizationSlug,
        Set<LifecycleAction> pieces,
        PieceScope pieceScope,
        Set<LifecycleAction> locations,
        Set<LifecycleAction> pieceTypes
) {
    public static NotificationPreferenceResponseDto from(Organization org, NotificationPreference pref) {
        return new NotificationPreferenceResponseDto(
                org.getId(),
                org.getName(),
                org.getSlug(),
                pref.getPieces() == null ? EnumSet.noneOf(LifecycleAction.class) : EnumSet.copyOf(pref.getPieces()),
                pref.getPieceScope(),
                pref.getLocations() == null ? EnumSet.noneOf(LifecycleAction.class) : EnumSet.copyOf(pref.getLocations()),
                pref.getPieceTypes() == null ? EnumSet.noneOf(LifecycleAction.class) : EnumSet.copyOf(pref.getPieceTypes())
        );
    }
}
