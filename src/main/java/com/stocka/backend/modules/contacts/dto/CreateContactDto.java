package com.stocka.backend.modules.contacts.dto;

/**
 * Payload for adding a person to the organization's contact directory. Only {@code name} is
 * required; {@code email}, when provided, must be unique within the organization.
 */
public class CreateContactDto {
    private String name;
    private String lastName;
    private String email;
    private String phone;
    private String notes;

    public String getName() { return name; }
    public CreateContactDto setName(String v) { this.name = v; return this; }

    public String getLastName() { return lastName; }
    public CreateContactDto setLastName(String v) { this.lastName = v; return this; }

    public String getEmail() { return email; }
    public CreateContactDto setEmail(String v) { this.email = v; return this; }

    public String getPhone() { return phone; }
    public CreateContactDto setPhone(String v) { this.phone = v; return this; }

    public String getNotes() { return notes; }
    public CreateContactDto setNotes(String v) { this.notes = v; return this; }
}
