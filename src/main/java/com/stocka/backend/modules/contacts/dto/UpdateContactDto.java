package com.stocka.backend.modules.contacts.dto;

/**
 * PATCH-partial payload. {@code null} fields are left unchanged. For the optional fields
 * ({@code lastName}, {@code email}, {@code phone}, {@code notes}) sending an empty/blank string
 * clears the value — blank is the canonical "not provided" form for them, mirroring how a piece's
 * {@code serialNumber} is cleared.
 */
public class UpdateContactDto {
    private String name;
    private String lastName;
    private String email;
    private String phone;
    private String notes;

    public String getName() { return name; }
    public UpdateContactDto setName(String v) { this.name = v; return this; }

    public String getLastName() { return lastName; }
    public UpdateContactDto setLastName(String v) { this.lastName = v; return this; }

    public String getEmail() { return email; }
    public UpdateContactDto setEmail(String v) { this.email = v; return this; }

    public String getPhone() { return phone; }
    public UpdateContactDto setPhone(String v) { this.phone = v; return this; }

    public String getNotes() { return notes; }
    public UpdateContactDto setNotes(String v) { this.notes = v; return this; }
}
