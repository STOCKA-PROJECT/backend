package com.stocka.backend.modules.contacts.dto;

/**
 * Result of linking a contact to an organization member: the updated contact plus the number of
 * pieces whose ownership was migrated from the contact to the member ({@code 0} when
 * {@code migratePieces} was not requested).
 */
public record LinkContactResponseDto(ContactResponseDto contact, int migratedPieces) {
}
