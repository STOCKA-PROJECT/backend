package com.stocka.backend.modules.search.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The XEYE list that indexes the pieces of one organization.
 *
 * <p>{@code pendingSince} is set whenever an element changes and cleared when a training is
 * launched, so the scheduler can batch many edits into one training.
 */
@Entity
@Table(name = "search_lists")
public class SearchList {

    @Id
    @Column(name = "organization_id")
    private Integer organizationId;

    @Column(name = "xeye_list_id", nullable = false)
    private Long xeyeListId;

    @Column(name = "list_name", nullable = false, length = 100)
    private String listName;

    @Column(name = "pending_since")
    private LocalDateTime pendingSince;

    @Column(name = "last_training_launched_at")
    private LocalDateTime lastTrainingLaunchedAt;

    @Column(name = "last_training_id")
    private Long lastTrainingId;

    public Integer getOrganizationId() { return organizationId; }
    public SearchList setOrganizationId(Integer organizationId) { this.organizationId = organizationId; return this; }

    public Long getXeyeListId() { return xeyeListId; }
    public SearchList setXeyeListId(Long xeyeListId) { this.xeyeListId = xeyeListId; return this; }

    public String getListName() { return listName; }
    public SearchList setListName(String listName) { this.listName = listName; return this; }

    public LocalDateTime getPendingSince() { return pendingSince; }
    public SearchList setPendingSince(LocalDateTime pendingSince) { this.pendingSince = pendingSince; return this; }

    public LocalDateTime getLastTrainingLaunchedAt() { return lastTrainingLaunchedAt; }
    public SearchList setLastTrainingLaunchedAt(LocalDateTime at) { this.lastTrainingLaunchedAt = at; return this; }

    public Long getLastTrainingId() { return lastTrainingId; }
    public SearchList setLastTrainingId(Long lastTrainingId) { this.lastTrainingId = lastTrainingId; return this; }
}
