package com.stocka.backend.modules.devices.entity;

import java.time.LocalDateTime;
import java.util.Date;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.annotations.UpdateTimestamp;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.stocka.backend.modules.organizations.entity.Organization;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * A Raspberry Pi player ("belén") registered in an {@link Organization}. The row carries the
 * credential the player uses for its heartbeat ({@link #tokenHash}: sha256 of the token, which is
 * shown once and never stored) and the latest report it sent ({@code last*} fields).
 *
 * <p>{@link #deviceId} is the uuid the player generates for itself and is bound by the first
 * heartbeat; a later heartbeat with a different uuid is accepted (the SD card may have been
 * re-imaged) and logged. {@link #lastOverrides} is a JSON array of port names cut by hand on the
 * player. {@link #lastIp} is the public address the heartbeat came from.
 *
 * <p>{@code createdBy} is mapped as a plain column (not a {@code @ManyToOne}) because {@code User}
 * carries {@code @SQLRestriction("deleted_at IS NULL")} and a soft-deleted creator must not break
 * hydration when listing devices.
 *
 * <p>On soft-delete the name is mangled with a "::deleted::ID" suffix so the
 * {@code uk_device_org_name} slot is freed.
 */
@Entity
@Table(
        name = "devices",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_device_org_name", columnNames = {"organization_id", "name"}),
                @UniqueConstraint(name = "uk_device_token_hash", columnNames = {"token_hash"})
        }
)
@SQLRestriction("deleted_at IS NULL")
public class Device {
    /** Board identifier recorded for every device until other boards are supported. */
    public static final String DEFAULT_BOARD = "rpi4b";

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Integer id;

    @ManyToOne
    @JoinColumn(name = "organization_id", referencedColumnName = "id", nullable = false)
    private Organization organization;

    @Column(name = "device_id", length = 36)
    private String deviceId;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(nullable = false, length = 32, columnDefinition = "VARCHAR(32) NOT NULL DEFAULT 'rpi4b'")
    private String board = DEFAULT_BOARD;

    @JsonIgnore
    @Column(name = "token_hash", nullable = false, length = 64, columnDefinition = "CHAR(64)")
    private String tokenHash;

    @Column(name = "token_prefix", nullable = false, length = 8)
    private String tokenPrefix;

    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;

    @Column(name = "last_state", length = 16)
    private String lastState;

    @Column(name = "last_show_hash", length = 80)
    private String lastShowHash;

    @Column(name = "last_show_name", length = 160)
    private String lastShowName;

    @Lob
    @Column(name = "last_overrides", columnDefinition = "TEXT")
    private String lastOverrides;

    @Lob
    @Column(name = "last_alerts", columnDefinition = "TEXT")
    private String lastAlerts;

    @Column(name = "last_version", length = 40)
    private String lastVersion;

    @Column(name = "last_ip", length = 45)
    private String lastIp;

    @Lob
    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "last_net_mode", length = 8)
    private String lastNetMode;

    @Column(name = "last_uptime_s")
    private Long lastUptimeS;

    @Column(name = "created_by")
    private Integer createdBy;

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

    public Device setId(Integer id) {
        this.id = id;
        return this;
    }

    public Organization getOrganization() {
        return organization;
    }

    public Device setOrganization(Organization organization) {
        this.organization = organization;
        return this;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public Device setDeviceId(String deviceId) {
        this.deviceId = deviceId;
        return this;
    }

    public String getName() {
        return name;
    }

    public Device setName(String name) {
        this.name = name;
        return this;
    }

    public String getBoard() {
        return board;
    }

    public Device setBoard(String board) {
        this.board = board;
        return this;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Device setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
        return this;
    }

    public String getTokenPrefix() {
        return tokenPrefix;
    }

    public Device setTokenPrefix(String tokenPrefix) {
        this.tokenPrefix = tokenPrefix;
        return this;
    }

    public LocalDateTime getLastSeenAt() {
        return lastSeenAt;
    }

    public Device setLastSeenAt(LocalDateTime lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
        return this;
    }

    public String getLastState() {
        return lastState;
    }

    public Device setLastState(String lastState) {
        this.lastState = lastState;
        return this;
    }

    public String getLastShowHash() {
        return lastShowHash;
    }

    public Device setLastShowHash(String lastShowHash) {
        this.lastShowHash = lastShowHash;
        return this;
    }

    public String getLastShowName() {
        return lastShowName;
    }

    public Device setLastShowName(String lastShowName) {
        this.lastShowName = lastShowName;
        return this;
    }

    /** @return JSON array of the alerts in the last heartbeat (see {@code DeviceAlertDto}) */
    public String getLastAlerts() {
        return lastAlerts;
    }

    public Device setLastAlerts(String lastAlerts) {
        this.lastAlerts = lastAlerts;
        return this;
    }

    public String getLastOverrides() {
        return lastOverrides;
    }

    public Device setLastOverrides(String lastOverrides) {
        this.lastOverrides = lastOverrides;
        return this;
    }

    public String getLastVersion() {
        return lastVersion;
    }

    public Device setLastVersion(String lastVersion) {
        this.lastVersion = lastVersion;
        return this;
    }

    public String getLastIp() {
        return lastIp;
    }

    public Device setLastIp(String lastIp) {
        this.lastIp = lastIp;
        return this;
    }

    public String getLastError() {
        return lastError;
    }

    public Device setLastError(String lastError) {
        this.lastError = lastError;
        return this;
    }

    public String getLastNetMode() {
        return lastNetMode;
    }

    public Device setLastNetMode(String lastNetMode) {
        this.lastNetMode = lastNetMode;
        return this;
    }

    public Long getLastUptimeS() {
        return lastUptimeS;
    }

    public Device setLastUptimeS(Long lastUptimeS) {
        this.lastUptimeS = lastUptimeS;
        return this;
    }

    public Integer getCreatedBy() {
        return createdBy;
    }

    public Device setCreatedBy(Integer createdBy) {
        this.createdBy = createdBy;
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

    public Device setDeletedAt(LocalDateTime deletedAt) {
        this.deletedAt = deletedAt;
        return this;
    }
}
