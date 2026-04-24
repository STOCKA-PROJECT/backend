package com.stocka.backend.modules.organizations.entity;

import java.util.Date;

import org.hibernate.annotations.CreationTimestamp;

import com.stocka.backend.modules.users.entity.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "organization_audit_logs")
public class OrganizationAuditLog {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "organization_id", referencedColumnName = "id", nullable = false)
    private Organization organization;

    @ManyToOne
    @JoinColumn(name = "actor_user_id", referencedColumnName = "id")
    private User actor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuditAction action;

    @ManyToOne
    @JoinColumn(name = "target_user_id", referencedColumnName = "id")
    private User targetUser;

    @Lob
    @Column(name = "payload", columnDefinition = "TEXT")
    private String payload;

    @CreationTimestamp
    @Column(updatable = false, name = "created_at")
    private Date createdAt;

    public Long getId() {
        return id;
    }

    public OrganizationAuditLog setId(Long id) {
        this.id = id;
        return this;
    }

    public Organization getOrganization() {
        return organization;
    }

    public OrganizationAuditLog setOrganization(Organization organization) {
        this.organization = organization;
        return this;
    }

    public User getActor() {
        return actor;
    }

    public OrganizationAuditLog setActor(User actor) {
        this.actor = actor;
        return this;
    }

    public AuditAction getAction() {
        return action;
    }

    public OrganizationAuditLog setAction(AuditAction action) {
        this.action = action;
        return this;
    }

    public User getTargetUser() {
        return targetUser;
    }

    public OrganizationAuditLog setTargetUser(User targetUser) {
        this.targetUser = targetUser;
        return this;
    }

    public String getPayload() {
        return payload;
    }

    public OrganizationAuditLog setPayload(String payload) {
        this.payload = payload;
        return this;
    }

    public Date getCreatedAt() {
        return createdAt;
    }
}
