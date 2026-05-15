package com.stocka.backend.modules.organizations.entity;

import java.time.LocalDateTime;
import java.util.Date;

import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.stocka.backend.modules.users.entity.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "organization_invitations")
public class OrganizationInvitation {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Integer id;

    @ManyToOne
    @JoinColumn(name = "organization_id", referencedColumnName = "id", nullable = false)
    private Organization organization;

    @Column(nullable = false)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrganizationRoleEnum role;

    @ManyToOne
    @JoinColumn(name = "invited_by_user_id", referencedColumnName = "id", nullable = false)
    private User invitedBy;

    @Column(nullable = false, unique = true, length = 100)
    private String token;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InvitationStatus status;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @CreationTimestamp
    @Column(updatable = false, name = "created_at")
    private Date createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Date updatedAt;

    @Column(name = "accepted_at")
    private LocalDateTime acceptedAt;

    @Version
    @ColumnDefault("0")
    private Long version;

    public Integer getId() {
        return id;
    }

    public OrganizationInvitation setId(Integer id) {
        this.id = id;
        return this;
    }

    public Organization getOrganization() {
        return organization;
    }

    public OrganizationInvitation setOrganization(Organization organization) {
        this.organization = organization;
        return this;
    }

    public String getEmail() {
        return email;
    }

    public OrganizationInvitation setEmail(String email) {
        this.email = email;
        return this;
    }

    public OrganizationRoleEnum getRole() {
        return role;
    }

    public OrganizationInvitation setRole(OrganizationRoleEnum role) {
        this.role = role;
        return this;
    }

    public User getInvitedBy() {
        return invitedBy;
    }

    public OrganizationInvitation setInvitedBy(User invitedBy) {
        this.invitedBy = invitedBy;
        return this;
    }

    public String getToken() {
        return token;
    }

    public OrganizationInvitation setToken(String token) {
        this.token = token;
        return this;
    }

    public InvitationStatus getStatus() {
        return status;
    }

    public OrganizationInvitation setStatus(InvitationStatus status) {
        this.status = status;
        return this;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public OrganizationInvitation setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
        return this;
    }

    public Date getCreatedAt() {
        return createdAt;
    }

    public Date getUpdatedAt() {
        return updatedAt;
    }

    public LocalDateTime getAcceptedAt() {
        return acceptedAt;
    }

    public OrganizationInvitation setAcceptedAt(LocalDateTime acceptedAt) {
        this.acceptedAt = acceptedAt;
        return this;
    }

    public Long getVersion() {
        return version;
    }
}
