package com.stocka.backend.modules.contacts.entity;

import java.time.LocalDateTime;
import java.util.Date;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.annotations.UpdateTimestamp;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.users.entity.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * A person in an organization's contact directory who can own pieces without being a registered
 * user or an organization member — e.g. an external client whose assets the organization stores.
 *
 * <p>{@link #email} is optional; when present it is unique per organization (case-insensitive,
 * enforced at the service layer against non-deleted rows only, like a piece's serial number).
 *
 * <p>{@link #linkedUser} is set when the external person later registers and joins the
 * organization: linking a contact to its member account lets the pieces it owns be migrated to
 * regular user ownership. It is mapped as a plain optional {@code @ManyToOne} — a contact never
 * requires a user to exist.
 */
@Entity
@Table(
        name = "contacts",
        indexes = {
                @Index(name = "idx_contacts_organization", columnList = "organization_id"),
                @Index(name = "idx_contacts_org_email", columnList = "organization_id, email")
        }
)
@SQLRestriction("deleted_at IS NULL")
public class Contact {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Integer id;

    @ManyToOne
    @JoinColumn(name = "organization_id", referencedColumnName = "id", nullable = false)
    private Organization organization;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "last_name", length = 100)
    private String lastName;

    @Column(length = 255)
    private String email;

    @Column(length = 40)
    private String phone;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @ManyToOne
    @JoinColumn(name = "linked_user_id", referencedColumnName = "id")
    private User linkedUser;

    @CreationTimestamp
    @Column(updatable = false, name = "created_at")
    private Date createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Date updatedAt;

    @JsonIgnore
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public Integer getId() { return id; }
    public Contact setId(Integer id) { this.id = id; return this; }

    public Organization getOrganization() { return organization; }
    public Contact setOrganization(Organization organization) { this.organization = organization; return this; }

    public String getName() { return name; }
    public Contact setName(String name) { this.name = name; return this; }

    public String getLastName() { return lastName; }
    public Contact setLastName(String lastName) { this.lastName = lastName; return this; }

    public String getEmail() { return email; }
    public Contact setEmail(String email) { this.email = email; return this; }

    public String getPhone() { return phone; }
    public Contact setPhone(String phone) { this.phone = phone; return this; }

    public String getNotes() { return notes; }
    public Contact setNotes(String notes) { this.notes = notes; return this; }

    public User getLinkedUser() { return linkedUser; }
    public Contact setLinkedUser(User linkedUser) { this.linkedUser = linkedUser; return this; }

    public Date getCreatedAt() { return createdAt; }
    public Date getUpdatedAt() { return updatedAt; }

    public LocalDateTime getDeletedAt() { return deletedAt; }
    public Contact setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; return this; }
}
