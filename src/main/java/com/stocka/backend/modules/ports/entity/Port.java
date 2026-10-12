package com.stocka.backend.modules.ports.entity;

import java.time.LocalDateTime;
import java.util.Date;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.annotations.UpdateTimestamp;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.stocka.backend.modules.contract.OutputKind;
import com.stocka.backend.modules.organizations.entity.Organization;

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
import jakarta.persistence.UniqueConstraint;

/**
 * A hardware port declared by an {@link Organization}: one output (or input) of the belén
 * controller, typed by a closed {@link OutputKind} from the output catalog. Examples: a relay on GPIO
 * 26, a WS281x strip on GPIO 13 with its DMA/channel/length, a segment of that strip, the angel
 * motor with its three pins, the audio output, or the start button.
 *
 * <p>{@link #kind} is immutable after creation. {@link #pin} holds the single GPIO number for kinds
 * that have one (relay, led_strip, button); it is {@code null} for the others. Everything else the
 * kind needs lives in {@link #configJson}, a JSON object validated against the catalog's config
 * schema for that kind (e.g. {@code {"strip":"LedStrip3","first":0,"last":148}} for a segment).
 *
 * <p>{@code pieceTypeId} is mapped as a plain column (not a {@code @ManyToOne}) on purpose: piece
 * types carry {@code @SQLRestriction("deleted_at IS NULL")}, so an eager association to a piece type
 * that gets soft-deleted while a port still references it would break hydration when listing ports.
 * It is {@code null} for infrastructure kinds (strips, button) and otherwise must point at a piece
 * type of the same organization whose own {@code kind} equals the port's.
 *
 * <p>{@link #parametersJson} is the legacy typed-parameter list kept for compatibility; the show
 * package only uses {@link #configJson}.
 *
 * <p>On soft-delete the name is mangled with a "::deleted::ID" suffix and the pin nulled so both
 * UNIQUE slots ({@code uk_port_org_name}, {@code uk_port_org_pin}) are freed.
 */
@Entity
@Table(
        name = "ports",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_port_org_name",
                        columnNames = {"organization_id", "name"}
                ),
                @UniqueConstraint(
                        name = "uk_port_org_pin",
                        columnNames = {"organization_id", "pin"}
                )
        }
)
@SQLRestriction("deleted_at IS NULL")
public class Port {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Integer id;

    @ManyToOne
    @JoinColumn(name = "organization_id", referencedColumnName = "id", nullable = false)
    private Organization organization;

    @Column(nullable = false, length = 160)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private OutputKind kind;

    @Column(name = "piece_type_id")
    private Integer pieceTypeId;

    @Column
    private Integer pin;

    @Lob
    @Column(name = "config_json", columnDefinition = "TEXT")
    private String configJson;

    @Column(nullable = false,
            columnDefinition = "INT NOT NULL DEFAULT 0")
    private int position = 0;

    @Lob
    @Column(name = "parameters_json", columnDefinition = "TEXT")
    private String parametersJson;

    @CreationTimestamp
    @Column(updatable = false, name = "created_at")
    private Date createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Date updatedAt;

    @JsonIgnore
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public Integer getId() {
        return id;
    }

    public Port setId(Integer id) {
        this.id = id;
        return this;
    }

    public Organization getOrganization() {
        return organization;
    }

    public Port setOrganization(Organization organization) {
        this.organization = organization;
        return this;
    }

    public String getName() {
        return name;
    }

    public Port setName(String name) {
        this.name = name;
        return this;
    }

    public OutputKind getKind() {
        return kind;
    }

    public Port setKind(OutputKind kind) {
        this.kind = kind;
        return this;
    }

    public String getConfigJson() {
        return configJson;
    }

    public Port setConfigJson(String configJson) {
        this.configJson = configJson;
        return this;
    }

    public Integer getPieceTypeId() {
        return pieceTypeId;
    }

    public Port setPieceTypeId(Integer pieceTypeId) {
        this.pieceTypeId = pieceTypeId;
        return this;
    }

    public Integer getPin() {
        return pin;
    }

    public Port setPin(Integer pin) {
        this.pin = pin;
        return this;
    }

    public int getPosition() {
        return position;
    }

    public Port setPosition(int position) {
        this.position = position;
        return this;
    }

    public String getParametersJson() {
        return parametersJson;
    }

    public Port setParametersJson(String parametersJson) {
        this.parametersJson = parametersJson;
        return this;
    }

    public Date getCreatedAt() {
        return createdAt;
    }

    public Date getUpdatedAt() {
        return updatedAt;
    }

    public LocalDateTime getDeletedAt() {
        return deletedAt;
    }

    public Port setDeletedAt(LocalDateTime deletedAt) {
        this.deletedAt = deletedAt;
        return this;
    }
}
