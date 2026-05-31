package com.stocka.backend.modules.users.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code PATCH /users/me/sessions/{deviceId}}. Only the display name
 * is mutable; everything else (UA, IP, timestamps) is observed and not editable.
 */
public class RenameSessionRequestDto {

    @NotBlank
    @Size(max = 120)
    private String displayName;

    public String getDisplayName() {
        return displayName;
    }

    public RenameSessionRequestDto setDisplayName(String displayName) {
        this.displayName = displayName;
        return this;
    }
}
