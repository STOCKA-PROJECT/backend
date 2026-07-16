package com.stocka.backend.modules.contacts.dto;

import java.util.Date;

import com.stocka.backend.modules.contacts.entity.Contact;

/** Single contact exposed in REST responses. */
public record ContactResponseDto(
        Integer id,
        Integer organizationId,
        String name,
        String lastName,
        String email,
        String phone,
        String notes,
        Integer linkedUserId,
        Date createdAt,
        Date updatedAt
) {
    /**
     * Builds the response from a {@link Contact}.
     *
     * @param contact source contact
     * @return the populated response DTO
     */
    public static ContactResponseDto from(Contact contact) {
        return new ContactResponseDto(
                contact.getId(),
                contact.getOrganization().getId(),
                contact.getName(),
                contact.getLastName(),
                contact.getEmail(),
                contact.getPhone(),
                contact.getNotes(),
                contact.getLinkedUser() == null ? null : contact.getLinkedUser().getId(),
                contact.getCreatedAt(),
                contact.getUpdatedAt()
        );
    }
}
