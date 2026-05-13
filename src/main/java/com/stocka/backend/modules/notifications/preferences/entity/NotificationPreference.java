package com.stocka.backend.modules.notifications.preferences.entity;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.EnumSet;
import java.util.Set;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.annotations.UpdateTimestamp;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.users.entity.User;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Per-user, per-organization opt-in for email notifications about lifecycle
 * events of the three notifiable resources (pieces, locations, piece types).
 *
 * <p>The three action columns are stored as CSV via
 * {@link LifecycleActionSetConverter}. An empty set means "do not notify me"
 * for that resource. Pieces additionally have a {@link PieceScope} so a user
 * can subscribe only to the pieces they own.
 *
 * <p>Rows are soft-deleted when the user leaves the organization or deletes
 * their account, mirroring the {@code OrganizationMember} pattern.
 */
@Entity
@Table(
        name = "notification_preferences",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_notif_pref_user_org",
                columnNames = {"user_id", "organization_id"}
        )
)
@SQLRestriction("deleted_at IS NULL")
public class NotificationPreference {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Integer id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", referencedColumnName = "id", nullable = false)
    private User user;

    @ManyToOne(optional = false)
    @JoinColumn(name = "organization_id", referencedColumnName = "id", nullable = false)
    private Organization organization;

    @Convert(converter = LifecycleActionSetConverter.class)
    @Column(name = "pieces_actions", nullable = false, length = 64)
    private Set<LifecycleAction> pieces = EnumSet.noneOf(LifecycleAction.class);

    @Enumerated(EnumType.STRING)
    @Column(name = "piece_scope", nullable = false, length = 16)
    private PieceScope pieceScope = PieceScope.OWNED_ONLY;

    @Convert(converter = LifecycleActionSetConverter.class)
    @Column(name = "locations_actions", nullable = false, length = 64)
    private Set<LifecycleAction> locations = EnumSet.noneOf(LifecycleAction.class);

    @Convert(converter = LifecycleActionSetConverter.class)
    @Column(name = "piece_types_actions", nullable = false, length = 64)
    private Set<LifecycleAction> pieceTypes = EnumSet.noneOf(LifecycleAction.class);

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
    public NotificationPreference setId(Integer id) { this.id = id; return this; }

    public User getUser() { return user; }
    public NotificationPreference setUser(User user) { this.user = user; return this; }

    public Organization getOrganization() { return organization; }
    public NotificationPreference setOrganization(Organization organization) {
        this.organization = organization;
        return this;
    }

    public Set<LifecycleAction> getPieces() { return pieces; }
    public NotificationPreference setPieces(Set<LifecycleAction> pieces) {
        this.pieces = normalize(pieces);
        return this;
    }

    public PieceScope getPieceScope() { return pieceScope; }
    public NotificationPreference setPieceScope(PieceScope pieceScope) {
        this.pieceScope = pieceScope == null ? PieceScope.OWNED_ONLY : pieceScope;
        return this;
    }

    public Set<LifecycleAction> getLocations() { return locations; }
    public NotificationPreference setLocations(Set<LifecycleAction> locations) {
        this.locations = normalize(locations);
        return this;
    }

    public Set<LifecycleAction> getPieceTypes() { return pieceTypes; }
    public NotificationPreference setPieceTypes(Set<LifecycleAction> pieceTypes) {
        this.pieceTypes = normalize(pieceTypes);
        return this;
    }

    public Date getCreatedAt() { return createdAt; }
    public Date getUpdatedAt() { return updatedAt; }

    public LocalDateTime getDeletedAt() { return deletedAt; }
    public NotificationPreference setDeletedAt(LocalDateTime deletedAt) {
        this.deletedAt = deletedAt;
        return this;
    }

    private static Set<LifecycleAction> normalize(Set<LifecycleAction> input) {
        if (input == null || input.isEmpty()) {
            return EnumSet.noneOf(LifecycleAction.class);
        }
        return EnumSet.copyOf(input);
    }
}
