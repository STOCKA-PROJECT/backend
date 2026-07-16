package com.stocka.backend.modules.contacts.dto;

/**
 * Body of {@code POST /organizations/{orgSlug}/contacts/{contactId}/link}: links a contact to the
 * organization member with user id {@code userId}. When {@code migratePieces} is {@code true} the
 * pieces currently owned by the contact are reassigned to the member as regular user ownership.
 */
public class LinkContactDto {
    private Integer userId;
    private Boolean migratePieces;

    public Integer getUserId() { return userId; }
    public LinkContactDto setUserId(Integer v) { this.userId = v; return this; }

    public Boolean getMigratePieces() { return migratePieces; }
    public LinkContactDto setMigratePieces(Boolean v) { this.migratePieces = v; return this; }
}
